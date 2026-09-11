[CmdletBinding()]
param()

Write-Host "[STOP] This historical Identity-only entry is retired because the local overlay now contains all 14 workloads." -ForegroundColor Yellow
Write-Host "[NEXT] Identity is already accepted. Continue with scripts/k3s/deploy-wallet-local.ps1 and its Import, Deploy, Verify actions."
exit 2

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string] $Message) {
    Write-Host "[STAGE] $Message" -ForegroundColor Cyan
}

function Write-Pass([string] $Message) {
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Assert-LastExitCode([string] $Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed (exit $LASTEXITCODE)."
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$previousLocation = Get-Location
$image = "suqihang/identity-service:0.1.0-k3s.1"
$evidenceDirectory = Join-Path $repositoryRoot "deploy/k3s/generated"
$evidencePath = Join-Path $evidenceDirectory "identity-local.json"

try {
    Set-Location -LiteralPath $repositoryRoot

    Write-Stage "Check the accepted Compose infrastructure stage"
    $infraEvidencePath = Join-Path $repositoryRoot "deploy/compose-infra/generated/infra-probe.json"
    if (-not (Test-Path -LiteralPath $infraEvidencePath -PathType Leaf)) {
        throw "Infrastructure evidence is missing. Run infra.ps1 -Action Probe -InfraHost 192.168.65.254 first."
    }
    $infraEvidence = Get-Content -Raw -LiteralPath $infraEvidencePath | ConvertFrom-Json
    if ($infraEvidence.status -ne "passed") {
        throw "The latest infrastructure probe did not pass."
    }
    $evidenceAge = [DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($infraEvidence.timestamp)
    if ($evidenceAge.TotalHours -gt 24) {
        throw "Infrastructure evidence is older than 24 hours. Rerun the Probe stage before deployment."
    }
    Write-Pass "Compose infrastructure and Pod connectivity evidence"

    Write-Stage "Validate local Identity secrets"
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/create-secrets.ps1 -Environment Local
    Assert-LastExitCode "Identity secret validation"

    Write-Stage "Validate the local release image"
    & docker image inspect $image --format "{{.Id}}" | Out-Null
    Assert-LastExitCode "Identity image inspection"

    $nodeResources = @(& kubectl get nodes -o name)
    Assert-LastExitCode "Kubernetes node discovery"
    $nodeNames = @($nodeResources |
        Where-Object { $_ -match '^node/[a-z0-9][a-z0-9.-]*$' } |
        ForEach-Object { $_.Substring('node/'.Length) })
    if ($nodeNames.Count -ne 1 -or $nodeNames[0] -notmatch '^[a-z0-9][a-z0-9.-]*$') {
        throw "This local loader requires exactly one Docker Desktop Kubernetes node."
    }
    $nodeName = $nodeNames[0]
    & docker inspect $nodeName --format "{{.Id}}" | Out-Null
    Assert-LastExitCode "Docker Desktop node inspection"

    $nodeImages = & docker exec $nodeName crictl images --output json
    Assert-LastExitCode "Kubernetes node image inspection"
    if (($nodeImages -join "`n") -notmatch [regex]::Escape("suqihang/identity-service")) {
        Write-Host "[RUN] Importing the verified image into the local Kubernetes node"
        $loadCommand = "docker image save $image | docker exec -i $nodeName ctr --namespace k8s.io images import -"
        & cmd.exe /d /s /c $loadCommand
        Assert-LastExitCode "Identity image import"
    }
    Write-Pass "Identity image is available to the Kubernetes node"

    Write-Stage "Render and validate the local Identity manifest"
    $rendered = & kubectl kustomize deploy/k3s/overlays/local
    Assert-LastExitCode "Local Kustomize render"
    if (($rendered -join "`n") -match 'change-me|replace-me|minipay-demo|localhost|127\.0\.0\.1|host\.docker\.internal') {
        throw "Rendered manifest contains a placeholder or an incorrect infrastructure host."
    }
    $rendered | kubectl apply --dry-run=server -f - -o name | Out-Null
    Assert-LastExitCode "Kubernetes server-side dry run"
    Write-Pass "Local Identity manifest"

    Write-Stage "Apply Identity and wait for readiness"
    & kubectl apply -k deploy/k3s/overlays/local
    Assert-LastExitCode "Identity apply"
    & kubectl rollout status deployment/identity-service -n minipay --timeout=180s
    Assert-LastExitCode "Identity rollout"

    $health = & kubectl get --raw "/api/v1/namespaces/minipay/services/http:identity-service:8080/proxy/actuator/health/readiness"
    Assert-LastExitCode "Identity readiness request"
    if (($health -join "") -notmatch '"status"\s*:\s*"UP"') {
        throw "Identity readiness endpoint did not report UP."
    }

    [void](New-Item -ItemType Directory -Path $evidenceDirectory -Force)
    $podName = & kubectl get pod -n minipay -l app.kubernetes.io/name=identity-service -o "jsonpath={.items[0].metadata.name}"
    $podImageId = & kubectl get pod $podName -n minipay -o "jsonpath={.status.containerStatuses[0].imageID}"
    [ordered]@{
        status = "passed"
        timestamp = [DateTimeOffset]::UtcNow.ToString("o")
        stage = "identity-local"
        image = $image
        pod = $podName
        imageId = $podImageId
        health = "UP"
        infraEvidence = "deploy/compose-infra/generated/infra-probe.json"
    } | ConvertTo-Json | Set-Content -LiteralPath $evidencePath -Encoding UTF8

    Write-Pass "Identity Pod, Service, real credentials and readiness"
    Write-Host "[INFO] Stage evidence: $evidencePath"
    Write-Host "[PENDING] No Gateway route or other business service was deployed by this stage."
}
catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    & kubectl get pods -n minipay -l app.kubernetes.io/name=identity-service -o wide 2>$null
    & kubectl logs -n minipay deployment/identity-service --tail=80 2>$null
    Write-Host "[STOP] Existing Compose volumes and data were not changed. Fix the reported item and rerun this stage."
    exit 1
}
finally {
    Set-Location -LiteralPath $previousLocation
}
