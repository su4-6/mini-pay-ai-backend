[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
function Assert-Exit([string] $Message) { if ($LASTEXITCODE -ne 0) { throw "$Message failed (exit $LASTEXITCODE)." } }
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$previousLocation = Get-Location
try {
    Set-Location -LiteralPath $repositoryRoot
    foreach ($command in @("docker", "kubectl", "mvn.cmd", "pnpm.cmd")) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required command was not found: $command" }
    }
    & docker info --format "{{.ServerVersion}}" | Out-Null
    Assert-Exit "Docker engine check"
    & kubectl get nodes | Out-Null
    Assert-Exit "Kubernetes node check"
    $infraPath = Join-Path $repositoryRoot "deploy/compose-infra/generated/infra-probe.json"
    if (-not (Test-Path -LiteralPath $infraPath -PathType Leaf)) { throw "Compose infrastructure evidence is missing." }
    $infra = Get-Content -Raw -LiteralPath $infraPath | ConvertFrom-Json
    if ($infra.status -ne "passed") { throw "The latest Compose infrastructure probe did not pass." }
    if (([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($infra.timestamp)).TotalHours -gt 24) { throw "Compose infrastructure evidence is older than 24 hours." }
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/prepare-local-workload-secrets.ps1
    Assert-Exit "Local Secret preparation"
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/check-config.ps1 -Environment Local
    Assert-Exit "Static K3s configuration check"
    $gatewayClass = (& kubectl get gatewayclass nginx -o jsonpath="{.status.conditions[?(@.type=='Accepted')].status}") -join ""
    Assert-Exit "NGINX GatewayClass check"
    if ($gatewayClass -ne "True") { throw "GatewayClass nginx is not Accepted. Install or repair NGINX Gateway Fabric first." }
    $rendered = @(& kubectl kustomize deploy/k3s/overlays/local)
    Assert-Exit "Local Kustomize render"
    $rendered | & kubectl apply --dry-run=server -f - -o name | Out-Null
    Assert-Exit "Local server-side dry run"
    Write-Host "[PASS] Local preflight and server-side dry run completed." -ForegroundColor Green
}
finally { Set-Location -LiteralPath $previousLocation }
