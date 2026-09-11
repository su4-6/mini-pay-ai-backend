[CmdletBinding()]
param(
    [ValidateSet("Local", "Server")]
    [string] $Environment = "Local"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$overlay = Join-Path $repositoryRoot "deploy/k3s/overlays/$($Environment.ToLowerInvariant())"
$rendered = (& kubectl kustomize $overlay) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Kustomize render failed for $Environment." }

$deploymentCount = ([regex]::Matches($rendered, '(?m)^kind: Deployment$')).Count
$serviceCount = ([regex]::Matches($rendered, '(?m)^kind: Service$')).Count
$routeCount = ([regex]::Matches($rendered, '(?m)^kind: HTTPRoute$')).Count
$expectedRoutes = if ($Environment -eq "Server") { 11 } else { 10 }
if ($deploymentCount -ne 14) { throw "Expected 14 Deployments, found $deploymentCount." }
if ($serviceCount -ne 14) { throw "Expected 14 Services, found $serviceCount." }
if ($routeCount -ne $expectedRoutes) { throw "Expected $expectedRoutes HTTPRoutes, found $routeCount." }
if ($rendered -match '(?im)image:\s*[^\s]+:latest\s*$') { throw "A workload uses the forbidden latest image tag." }
if ($rendered -match '(?i)(change-me|replace-me|minipay-demo)') { throw "Rendered configuration contains a placeholder/demo marker." }

$workloads = Join-Path $repositoryRoot "deploy/k3s/base/workloads"
foreach ($service in @("identity", "payment", "wallet", "commerce", "agent")) {
    $text = Get-Content -Raw -LiteralPath (Join-Path $workloads "$service.yaml")
    $expected = "$($service.ToUpperInvariant())_MYSQL_URL"
    if ($text -notmatch [regex]::Escape("key: $expected")) { throw "$service does not map MYSQL_URL from $expected." }
}
foreach ($file in Get-ChildItem -LiteralPath $workloads -File -Filter "*.yaml") {
    $text = Get-Content -Raw -LiteralPath $file.FullName
    if ($text -notmatch 'automountServiceAccountToken:\s*false') { throw "$($file.Name) omits service-account-token isolation." }
    if ($text -notmatch 'readOnlyRootFilesystem:\s*true') { throw "$($file.Name) omits the read-only root filesystem." }
    if ($text -notmatch 'allowPrivilegeEscalation:\s*false') { throw "$($file.Name) omits privilege-escalation protection." }
}

if ($Environment -eq "Server") {
    if ($rendered -match '(?i)(192\.168\.|host\.docker\.internal|\.minipay\.localhost|127\.0\.0\.1|localhost:)') {
        throw "Server render contains a local-only host or domain."
    }
} elseif ($rendered -notmatch 'identity\.minipay\.localhost') {
    throw "Local issuer/route domain is missing."
}

Write-Host "[PASS] $Environment render: 14 Deployments, 14 Services, $expectedRoutes HTTPRoutes and fixed images." -ForegroundColor Green
Write-Host "[PASS] Workload hardening and five database URL isolation mappings." -ForegroundColor Green
