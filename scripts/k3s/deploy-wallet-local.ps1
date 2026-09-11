[CmdletBinding()]
param(
    [ValidateSet("Import", "Deploy", "Verify", "All")]
    [string] $Action = "All",
    [string] $Registry = "suqihang",
    [string] $Tag = "0.1.0-k3s.1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "infra-common.ps1")

function Write-Stage([string] $Message) { Write-Host "[STAGE] $Message" -ForegroundColor Cyan }
function Write-Pass([string] $Message) { Write-Host "[PASS] $Message" -ForegroundColor Green }
function Assert-Exit([string] $Message) { if ($LASTEXITCODE -ne 0) { throw "$Message failed (exit $LASTEXITCODE)." } }

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$overlay = Join-Path $repositoryRoot "deploy/k3s/overlays/local"
$private = Join-Path $overlay "private"
$evidenceDirectory = Join-Path $repositoryRoot "deploy/k3s/generated"
$evidencePath = Join-Path $evidenceDirectory "wallet-local.json"
$images = @("$Registry/identity-service`:$Tag", "$Registry/wallet-service`:$Tag")
$previousLocation = Get-Location

function Import-Images {
    Write-Stage "Import Identity and Wallet images into every local Kubernetes node"
    $nodes = @(& kubectl get nodes -o name)
    Assert-Exit "Kubernetes node discovery"
    if ($nodes.Count -lt 1) { throw "No Kubernetes node was found." }
    foreach ($resource in $nodes) {
        if ($resource -notmatch '^node/([a-z0-9][a-z0-9.-]*)$') { throw "Unexpected node name: $resource" }
        $node = $Matches[1]
        & docker inspect $node --format "{{.Id}}" | Out-Null
        Assert-Exit "Docker container lookup for Kubernetes node $node"
        $nodeImages = (& docker exec $node crictl images --output json) -join "`n"
        Assert-Exit "Image inventory for Kubernetes node $node"
        foreach ($image in $images) {
            & docker image inspect $image --format "{{.Id}}" | Out-Null
            Assert-Exit "Local image inspection for $image"
            if ($nodeImages -notmatch [regex]::Escape($image)) {
                Write-Host "[RUN] $image -> $node"
                $safe = "docker image save $image | docker exec -i $node ctr --namespace k8s.io images import -"
                & cmd.exe /d /s /c $safe
                Assert-Exit "Image import for $image"
            }
        }
    }
    Write-Pass "Identity and Wallet images are available to the Kubernetes node(s)"
}

function Invoke-ApplyFile([string] $Path) {
    & kubectl apply --dry-run=server -f $Path -o name | Out-Null
    Assert-Exit "Server-side validation for $Path"
    & kubectl apply -f $Path | Out-Null
    Assert-Exit "Apply $Path"
}

function Invoke-ApplyGenerated([string[]] $CreateArguments, [string] $Description) {
    $yaml = @(& kubectl @CreateArguments --dry-run=client -o yaml)
    Assert-Exit "Render $Description"
    $yaml | & kubectl apply --dry-run=server -f - -o name | Out-Null
    Assert-Exit "Server-side validation for $Description"
    $yaml | & kubectl apply -f - | Out-Null
    Assert-Exit "Apply $Description"
}

function Deploy-Wallet {
    Write-Stage "Prepare and validate local private files"
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/prepare-local-workload-secrets.ps1
    Assert-Exit "Private configuration preparation"

    $infraEvidencePath = Join-Path $repositoryRoot "deploy/compose-infra/generated/infra-probe.json"
    if (-not (Test-Path -LiteralPath $infraEvidencePath -PathType Leaf)) { throw "Compose infrastructure evidence is missing." }
    $infraEvidence = Get-Content -Raw -LiteralPath $infraEvidencePath | ConvertFrom-Json
    if ($infraEvidence.status -ne "passed") { throw "The latest Compose infrastructure probe did not pass." }

    Write-Stage "Apply only Identity and Wallet resources"
    Invoke-ApplyFile (Join-Path $repositoryRoot "deploy/k3s/base/namespace.yaml")
    Invoke-ApplyFile (Join-Path $repositoryRoot "deploy/k3s/base/config/runtime-config.yaml")
    Invoke-ApplyGenerated @("create", "configmap", "minipay-environment", "-n", "minipay", "--from-env-file=$(Join-Path $overlay 'runtime.env')") "local environment ConfigMap"
    Invoke-ApplyGenerated @("create", "secret", "generic", "identity-runtime", "-n", "minipay", "--from-env-file=$(Join-Path $private 'identity.env')") "Identity runtime Secret"
    Invoke-ApplyGenerated @("create", "secret", "generic", "identity-jwt", "-n", "minipay", "--from-file=jwt-private.pem=$(Join-Path $private 'jwt-private.pem')", "--from-file=jwt-public.pem=$(Join-Path $private 'jwt-public.pem')") "Identity JWT Secret"
    Invoke-ApplyGenerated @("create", "secret", "generic", "wallet-runtime", "-n", "minipay", "--from-env-file=$(Join-Path $private 'wallet.env')") "Wallet runtime Secret"
    Invoke-ApplyFile (Join-Path $repositoryRoot "deploy/k3s/base/workloads/identity.yaml")
    Invoke-ApplyFile (Join-Path $repositoryRoot "deploy/k3s/base/workloads/wallet.yaml")
    & kubectl rollout status deployment/identity-service -n minipay --timeout=240s
    Assert-Exit "Identity rollout"
    & kubectl rollout status deployment/wallet-service -n minipay --timeout=240s
    Assert-Exit "Wallet rollout"
    Write-Pass "Wallet Deployment is Ready; no other workload was applied"
}

function Verify-Wallet {
    Write-Stage "Verify Wallet health, endpoint and dependency wiring"
    $health = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:wallet-service:8080/proxy/actuator/health/readiness") -join ""
    Assert-Exit "Wallet readiness request"
    if ($health -notmatch '"status"\s*:\s*"UP"') { throw "Wallet readiness did not report UP." }

    $slices = (& kubectl get endpointslice -n minipay -l kubernetes.io/service-name=wallet-service -o json) -join "`n" | ConvertFrom-Json
    $ready = @($slices.items.endpoints | Where-Object { $_.conditions.ready -eq $true })
    if ($ready.Count -lt 1) { throw "wallet-service has no Ready EndpointSlice endpoint." }

    $deployment = (& kubectl get deployment wallet-service -n minipay -o json) -join "`n" | ConvertFrom-Json
    $mysqlSource = @($deployment.spec.template.spec.containers[0].env | Where-Object { $_.name -eq "MYSQL_URL" })
    if ($mysqlSource.Count -ne 1 -or $mysqlSource[0].valueFrom.configMapKeyRef.key -ne "WALLET_MYSQL_URL") {
        throw "Wallet MYSQL_URL is not isolated to WALLET_MYSQL_URL."
    }
    $pod = (& kubectl get pod -n minipay -l app.kubernetes.io/name=wallet-service -o jsonpath="{.items[0].metadata.name}") -join ""
    Assert-Exit "Wallet Pod lookup"
    & kubectl exec -n minipay $pod -- wget -qO- http://identity-service:8080/oauth2/jwks | Out-Null
    Assert-Exit "Wallet to Identity JWK"
    $runtime = Read-InfraEnvironment (Join-Path $overlay "runtime.env")
    $seataAddress = ([string]$runtime.SEATA_SERVER_ADDR).Split(':', 2)
    if ($seataAddress.Count -ne 2) { throw "SEATA_SERVER_ADDR is invalid." }
    & kubectl exec -n minipay $pod -- nc -z -w 3 $runtime.RABBITMQ_HOST $runtime.RABBITMQ_PORT
    Assert-Exit "Wallet to RabbitMQ"
    & kubectl exec -n minipay $pod -- nc -z -w 3 $seataAddress[0] $seataAddress[1]
    Assert-Exit "Wallet to Seata"

    $logs = (& kubectl logs -n minipay $pod --tail=250) -join "`n"
    if ($logs -match '(?i)(access denied|authentication failed|unknown database|communications link failure|failed to obtain jdbc|connection refused)') {
        throw "Wallet logs contain a dependency/authentication failure. Inspect the redacted Pod logs."
    }

    [void](New-Item -ItemType Directory -Path $evidenceDirectory -Force)
    [ordered]@{
        status = "passed"
        timestamp = [DateTimeOffset]::UtcNow.ToString("o")
        stage = "wallet-local"
        image = $deployment.spec.template.spec.containers[0].image
        pod = $pod
        readyEndpoints = $ready.Count
        health = "UP"
        databaseKey = "WALLET_MYSQL_URL"
        identityJwk = "reachable"
        rabbitmq = "reachable"
        seata = "reachable"
        infraEvidence = "deploy/compose-infra/generated/infra-probe.json"
    } | ConvertTo-Json | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    Write-Pass "Wallet health, Service endpoint, database isolation, RabbitMQ, Seata and Identity JWK"
    Write-Host "[INFO] Stage evidence: $evidencePath"
    Write-Host "[PENDING] TCC business behavior is deliberately reserved for the Payment/Wallet stage."
}

try {
    Set-Location -LiteralPath $repositoryRoot
    if ($Action -in @("Import", "All")) { Import-Images }
    if ($Action -in @("Deploy", "All")) { Deploy-Wallet }
    if ($Action -in @("Verify", "All")) { Verify-Wallet }
}
catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "[STOP] Existing Compose volumes and data were not changed."
    exit 1
}
finally { Set-Location -LiteralPath $previousLocation }
