[CmdletBinding()]
param(
    [string] $Registry = "suqihang",
    [string] $MiniPayTag = "0.1.0-k3s.1",
    [string] $YShopTag = "0.1.0-k3s.2-lite.1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
function Assert-Exit([string] $Message) { if ($LASTEXITCODE -ne 0) { throw "$Message failed (exit $LASTEXITCODE)." } }
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$previousLocation = Get-Location
$ordered = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "yshop-server",
    "agent-service", "consumer-bff", "management-bff", "admin-bff",
    "merchant-web", "ops-web", "admin-web", "yshop-food-h5", "yshop-admin-web"
)
try {
    Set-Location -LiteralPath $repositoryRoot
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/preflight-local.ps1
    Assert-Exit "Local preflight"
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/import-local-images.ps1 -Registry $Registry -MiniPayTag $MiniPayTag -YShopTag $YShopTag
    Assert-Exit "Local image import"
    & kubectl apply -k deploy/k3s/overlays/local
    Assert-Exit "Local Kustomize apply"
    # The local tag is stable, so applying unchanged YAML alone would not replace
    # Pods after an image rebuild. Restart every Deployment after refreshing the
    # node image store to guarantee the running Pods use this build.
    foreach ($name in $ordered) {
        & kubectl rollout restart "deployment/$name" -n minipay
        Assert-Exit "Rollout restart for $name"
    }
    foreach ($name in $ordered) {
        & kubectl rollout status "deployment/$name" -n minipay --timeout=360s
        Assert-Exit "Rollout for $name"
    }
    Write-Host "[PASS] All 14 local Deployments rolled out in dependency order." -ForegroundColor Green
    Write-Host "[NEXT] Run scripts/k3s/acceptance-local.ps1. Business acceptance remains a separate gate."
}
catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    & kubectl get pods -n minipay -o wide 2>$null
    Write-Host "[STOP] Existing Compose volumes and data were retained."
    exit 1
}
finally { Set-Location -LiteralPath $previousLocation }
