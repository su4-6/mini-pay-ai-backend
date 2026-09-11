[CmdletBinding()]
param(
    [string] $YShopBoot3Dir = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
if ([string]::IsNullOrWhiteSpace($YShopBoot3Dir)) {
    $YShopBoot3Dir = Join-Path $repositoryRoot "integrations/yshop/server"
}
$root = (Resolve-Path $YShopBoot3Dir).Path
$required = @(
    "pom.xml",
    "yshop-server/pom.xml",
    "sql/yixiang-drink-open.sql",
    "sql/migrations/minipay/V001__minipay_food_integration.sql",
    "sql/migrations/minipay/V002__minipay_authorization_profiles.sql",
    "sql/migrations/minipay/V003__minipay_address_location_drafts.sql"
    "sql/migrations/minipay/V004__widen_api_error_log_id.sql"
    "sql/migrations/minipay/V005__widen_order_number_id.sql"
)
foreach ($relative in $required) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required YShop source file is missing: $relative" }
}
$localConfig = Join-Path $root "yshop-server/src/main/resources/application-local.yaml"
$text = Get-Content -Raw -LiteralPath $localConfig
if ($text -match '(?m)^\s*(secret|client-secret|app-id):\s*[^$\s][^\s#]*') {
    Write-Host "[BLOCKED-PRODUCTION] Legacy YShop local configuration contains hard-coded third-party credential-like values." -ForegroundColor Yellow
    Write-Host "[INFO] K3s does not copy these values. Rotate/remove them before server acceptance."
}
Write-Host "[PASS] YShop source, server module and six required SQL inputs exist." -ForegroundColor Green
