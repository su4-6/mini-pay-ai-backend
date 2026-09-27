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

# Expected inventory is derived from the base manifests instead of being hardcoded.
# Hardcoded counts went stale every time a workload was added (the C-end switch added
# consumer-web and miling-service and silently invalidated the old 14/14/10 numbers).
# Deriving them keeps the guard's real intent -- "the overlay must not add or drop
# workloads relative to base" -- while surviving future additions.
$workloadDirectory = Join-Path $repositoryRoot "deploy/k3s/base/workloads"
$routeDirectory = Join-Path $repositoryRoot "deploy/k3s/base/routes"

function Get-DocumentNames([string] $Text, [string] $Kind) {
    $names = @()
    foreach ($document in ($Text -split '(?m)^---\s*$')) {
        if ($document -notmatch "(?m)^kind:\s*$Kind\s*$") { continue }
        if ($document -match '(?m)^metadata:\s*\{\s*name:\s*([\w.\-]+)') { $names += $Matches[1]; continue }
        if ($document -match '(?m)^metadata:\s*$[\s\S]*?(?m)^  name:\s*([\w.\-]+)') { $names += $Matches[1] }
    }
    return $names
}

# Every base workload file declares exactly one Deployment and one Service. Skip the
# yshop-lite sub-package (kustomization.yaml), which only lists resources and is not
# part of base -- it used to make the hardening loop below fail on a missing field.
$baseWorkloadFiles = @(
    Get-ChildItem -LiteralPath $workloadDirectory -File -Filter "*.yaml" |
        Where-Object { (Get-Content -Raw -LiteralPath $_.FullName) -match '(?m)^kind: Deployment$' }
)
# Workload names come from the manifest contents, not the file names: the file is
# agent.yaml while the Deployment is agent-service, so comparing file base names
# against rendered names would always fail.
$expectedWorkloadNames = @(
    $baseWorkloadFiles |
        ForEach-Object { Get-DocumentNames (Get-Content -Raw -LiteralPath $_.FullName) "Deployment" } |
        Sort-Object -Unique
)
$baseRouteText = (@(Get-ChildItem -LiteralPath $routeDirectory -File -Filter "*.yaml" |
        ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }) -join "`n---`n")
$expectedRouteNames = @(Get-DocumentNames $baseRouteText "HTTPRoute" | Sort-Object -Unique)

# Overlays only patch fields; the rendered workload and route names must match base.
$renderedDeployments = @(Get-DocumentNames $rendered "Deployment" | Sort-Object -Unique)
$renderedServices = @(Get-DocumentNames $rendered "Service" | Sort-Object -Unique)
$renderedRoutes = @(Get-DocumentNames $rendered "HTTPRoute" | Sort-Object -Unique)

$missing = @($expectedWorkloadNames | Where-Object { $renderedDeployments -notcontains $_ })
$unexpected = @($renderedDeployments | Where-Object { $expectedWorkloadNames -notcontains $_ })
if ($missing.Count -gt 0) { throw "$Environment render is missing Deployments: $($missing -join ', ')." }
if ($unexpected.Count -gt 0) { throw "$Environment render has unexpected Deployments: $($unexpected -join ', ')." }
if ($renderedServices.Count -ne $expectedWorkloadNames.Count) {
    throw "Expected $($expectedWorkloadNames.Count) Services, found $($renderedServices.Count)."
}
$missingRoutes = @($expectedRouteNames | Where-Object { $renderedRoutes -notcontains $_ })
if ($missingRoutes.Count -gt 0) { throw "$Environment render is missing HTTPRoutes: $($missingRoutes -join ', ')." }
# Overlays may add routes (the Server overlay adds an HTTP-to-HTTPS redirect) but must
# not drop base ones, which the check above already guarantees.
if ($renderedRoutes.Count -lt $expectedRouteNames.Count) {
    throw "Expected at least $($expectedRouteNames.Count) HTTPRoutes, found $($renderedRoutes.Count)."
}
if ($rendered -match '(?im)image:\s*[^\s]+:latest\s*$') { throw "A workload uses the forbidden latest image tag." }
# Secrets are rendered as base64, so a placeholder inside a private env file does not
# trip this check; it only catches plaintext demo markers in the manifests.
if ($rendered -match '(?i)(change-me|replace-me|minipay-demo)') { throw "Rendered configuration contains a placeholder/demo marker." }

foreach ($service in @("identity", "payment", "wallet", "commerce", "agent")) {
    $text = Get-Content -Raw -LiteralPath (Join-Path $workloadDirectory "$service.yaml")
    $expected = "$($service.ToUpperInvariant())_MYSQL_URL"
    if ($text -notmatch [regex]::Escape("key: $expected")) { throw "$service does not map MYSQL_URL from $expected." }
}
foreach ($file in $baseWorkloadFiles) {
    $text = Get-Content -Raw -LiteralPath $file.FullName
    if ($text -notmatch 'automountServiceAccountToken:\s*false') { throw "$($file.Name) omits service-account-token isolation." }
    # miling-service defaults to authentication ON (miling.security.enabled matchIfMissing=true) and
    # only that switch can open every endpoint. No workload may carry the bypass into a deployment.
    if ($text -match 'MILING_SECURITY_ENABLED') {
        throw "$($file.Name) overrides the Miling authentication switch; keep authentication default-on in manifests."
    }
}
# Container-level hardening. maintenance-page is the single documented exception: it serves the
# food "maintenance" notice from the stock nginx:1.27-alpine image, whose master process starts as
# root and writes /var/cache/nginx, so readOnlyRootFilesystem / runAsNonRoot cannot be set until it
# is rebuilt on an unprivileged base image -- a separate change, not a manifest fix.
# acceptance-local.ps1 excludes the same workload from its probe gate for the same reason.
$hardeningExempt = @("maintenance-page.yaml")
foreach ($file in ($baseWorkloadFiles | Where-Object { $hardeningExempt -notcontains $_.Name })) {
    $text = Get-Content -Raw -LiteralPath $file.FullName
    if ($text -notmatch 'readOnlyRootFilesystem:\s*true') { throw "$($file.Name) omits the read-only root filesystem." }
    if ($text -notmatch 'allowPrivilegeEscalation:\s*false') { throw "$($file.Name) omits privilege-escalation protection." }
}

# miling-service must switch the model on explicitly: MILING_MODEL_ENABLED defaults to false and
# that default is a silent failure mode (the assistant answers nothing). agent-service shipped with
# the same default and the AI stayed mute until change #55 set it. Guard against a repeat.
$milingWorkload = Join-Path $workloadDirectory "miling-service.yaml"
if (-not (Test-Path -LiteralPath $milingWorkload)) { throw "miling-service.yaml is missing from the workload inventory." }
if ((Get-Content -Raw -LiteralPath $milingWorkload) -notmatch 'MILING_MODEL_ENABLED,\s*value:\s*"?true"?') {
    throw "miling-service.yaml must set MILING_MODEL_ENABLED=true; its default is false and the assistant would stay mute."
}

if ($Environment -eq "Server") {
    # Judged per document so the failure names the offending workload instead of only a substring.
    # The retired food stack is skipped: yshop-server's inline pay/refund notify URLs in base are
    # still local development values, and that stack is at replicas 0 with its routes pointing at
    # maintenance-page -- it serves nothing in this delivery. Every other workload must be clean.
    $retiredWorkloads = @("yshop-server", "yshop-food-h5", "yshop-admin-web")
    $localHostPattern = '(?i)(192\.168\.|host\.docker\.internal|\.minipay\.localhost|127\.0\.0\.1|localhost[:/])'
    foreach ($document in ($rendered -split '(?m)^---\s*$')) {
        $name = if ($document -match '(?m)^  name:\s*([\w.\-]+)\s*$') { $Matches[1] } else { "<unnamed>" }
        if ($retiredWorkloads -contains $name) { continue }
        if ($document -match $localHostPattern) {
            throw "Server render document '$name' contains a local-only host or domain."
        }
    }
} elseif ($rendered -notmatch 'identity\.minipay\.localhost') {
    throw "Local issuer/route domain is missing."
}

Write-Host "[PASS] $Environment render: $($renderedDeployments.Count) Deployments, $($renderedServices.Count) Services, $($renderedRoutes.Count) HTTPRoutes and fixed images." -ForegroundColor Green
Write-Host "[PASS] Workload hardening and five database URL isolation mappings." -ForegroundColor Green
