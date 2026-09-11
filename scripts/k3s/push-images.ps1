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
$names = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "agent-service",
    "consumer-bff", "management-bff", "admin-bff", "merchant-web", "ops-web", "admin-web",
    "yshop-server", "yshop-food-h5", "yshop-admin-web"
)
$deploymentByImage = @{
    "identity-service" = "identity-service"; "wallet-service" = "wallet-service"; "payment-service" = "payment-service"
    "commerce-service" = "commerce-service"; "agent-service" = "agent-service"; "consumer-bff" = "consumer-bff"
    "management-bff" = "management-bff"; "admin-bff" = "admin-bff"; "merchant-web" = "merchant-web"
    "ops-web" = "ops-web"; "admin-web" = "admin-web"; "yshop-server" = "yshop-server"
    "yshop-food-h5" = "yshop-food-h5"; "yshop-admin-web" = "yshop-admin-web"
}
$componentDirectory = Join-Path $repositoryRoot "deploy/k3s/overlays/server/private/digests"
$componentPath = Join-Path $componentDirectory "kustomization.yaml"

foreach ($name in $names) {
    $tag = if ($name -like "yshop-*") { $YShopTag } else { $MiniPayTag }
    $image = "$Registry/$name`:$tag"
    & docker image inspect $image --format "{{.Id}}" | Out-Null
    Assert-Exit "Local image inspection for $image"
}
foreach ($name in $names) {
    $tag = if ($name -like "yshop-*") { $YShopTag } else { $MiniPayTag }
    $image = "$Registry/$name`:$tag"
    Write-Host "[PUSH] $image"
    & docker push $image
    Assert-Exit "Push for $image"
}

$lines = @(
    "apiVersion: kustomize.config.k8s.io/v1alpha1",
    "kind: Component",
    "images:"
)
foreach ($name in $names) {
    $tag = if ($name -like "yshop-*") { $YShopTag } else { $MiniPayTag }
    $image = "$Registry/$name`:$tag"
    $digests = @(& docker image inspect $image --format "{{range .RepoDigests}}{{println .}}{{end}}")
    Assert-Exit "Digest lookup for $image"
    $match = @($digests | Where-Object { $_ -match "^$([regex]::Escape($Registry))/$([regex]::Escape($name))@sha256:[a-f0-9]{64}$" } | Select-Object -First 1)
    if ($match.Count -ne 1) { throw "A registry digest was not found after pushing $image." }
    $digest = ($match[0] -split '@', 2)[1]
    $lines += "  - name: $Registry/$name"
    $lines += "    newName: $Registry/$name"
    $lines += "    digest: $digest"
}
[void](New-Item -ItemType Directory -Path $componentDirectory -Force)
[IO.File]::WriteAllLines($componentPath, $lines, [Text.UTF8Encoding]::new($false))
Write-Host "[PASS] All images pushed and the ignored server digest component was generated." -ForegroundColor Green
Write-Host "[INFO] $componentPath"
