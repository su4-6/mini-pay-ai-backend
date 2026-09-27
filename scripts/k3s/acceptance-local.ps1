[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
function Assert-Exit([string] $Message) { if ($LASTEXITCODE -ne 0) { throw "$Message failed (exit $LASTEXITCODE)." } }
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$evidenceDirectory = Join-Path $repositoryRoot "deploy/k3s/generated"
$evidencePath = Join-Path $evidenceDirectory "local-platform.json"

# Expected workload inventory. Adding or removing a workload in the manifests without
# updating this list should fail loudly.
# maintenance-page (the food maintenance page) is intentionally not gated here, matching
# this script's original scope.
$deployments = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "agent-service",
    "consumer-bff", "management-bff", "admin-bff",
    "merchant-web", "ops-web", "admin-web", "consumer-web", "miling-service",
    "yshop-server", "yshop-food-h5", "yshop-admin-web"
)

# Workloads intentionally scaled to zero for memory reclaim (see deploy/k3s/README.md
# "Memory reclaim and the C-end switch"):
#   commerce-service    food-ordering backend; the food stack is offline
#   agent-service       previous Miling implementation, replaced by miling-service
#   yshop-*             the food stack (changes #47/#48)
# These must NOT be required to be Ready (that assertion would always fail), but they
# must be asserted to have no Ready endpoints -- otherwise a scale-down that silently
# did nothing would still pass. Local environments usually run them at 1 replica,
# which also passes.
$scaledToZero = @("commerce-service", "agent-service", "yshop-server", "yshop-food-h5", "yshop-admin-web")

# Grouped by health-check style (Java services: 8080 + actuator; static sites: 80 + /healthz).
$javaServices = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "agent-service",
    "consumer-bff", "management-bff", "admin-bff", "miling-service"
)
$webServices = @("merchant-web", "ops-web", "admin-web", "consumer-web", "yshop-food-h5", "yshop-admin-web")

# Total HTTPRoutes: merchant/ops/admin + public API routes + consumer-web + two yshop routes.
$expectedRoutes = 11

$previousLocation = Get-Location

try {
    Set-Location -LiteralPath $repositoryRoot
    $results = @()
    $running = @()
    foreach ($name in $deployments) {
        $deployment = (& kubectl get deployment $name -n minipay -o json) -join "`n" | ConvertFrom-Json
        Assert-Exit "Deployment lookup for $name"
        $desired = [int]($deployment.spec.replicas)
        $readyCount = if ($null -eq $deployment.status.readyReplicas) { 0 } else { [int]$deployment.status.readyReplicas }
        if ($readyCount -ne $desired) { throw "$name is not fully Ready ($readyCount/$desired)." }
        $slices = (& kubectl get endpointslice -n minipay -l "kubernetes.io/service-name=$name" -o json) -join "`n" | ConvertFrom-Json
        Assert-Exit "EndpointSlice lookup for $name"
        $ready = @($slices.items.endpoints | Where-Object { $_.conditions.ready -eq $true })
        if ($desired -eq 0) {
            if ($ready.Count -ne 0) { throw "$name is expected to be scaled to zero but still has $($ready.Count) Ready endpoints." }
        } elseif ($ready.Count -lt 1) {
            throw "$name has no Ready EndpointSlice endpoint."
        } else {
            $running += $name
        }
        $results += [ordered]@{
            name = $name
            desiredReplicas = $desired
            readyReplicas = $readyCount
            readyEndpoints = $ready.Count
            scaledToZero = ($scaledToZero -contains $name)
            image = $deployment.spec.template.spec.containers[0].image
        }
    }

    # Only health check what is actually running: a zero-replica service has no backend.
    $healthChecked = 0
    foreach ($name in $javaServices) {
        if ($running -notcontains $name) { continue }
        $health = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:$name`:8080/proxy/actuator/health/readiness") -join ""
        Assert-Exit "Readiness request for $name"
        if ($health -notmatch '"status"\s*:\s*"UP"') { throw "$name readiness did not report UP." }
        $healthChecked++
    }
    if ($running -contains "yshop-server") {
        $yshopHealth = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:yshop-server`:48080/proxy/actuator/health") -join ""
        Assert-Exit "YShop readiness request"
        if ($yshopHealth -notmatch '"status"\s*:\s*"UP"') { throw "YShop health did not report UP." }
        $healthChecked++
    }
    foreach ($name in $webServices) {
        if ($running -notcontains $name) { continue }
        $health = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:$name`:80/proxy/healthz") -join ""
        Assert-Exit "Static health request for $name"
        if ($health.Trim() -ne "ok") { throw "$name /healthz did not return ok." }
        $healthChecked++
    }

    $gateway = (& kubectl get gateway minipay-gateway -n minipay -o json) -join "`n" | ConvertFrom-Json
    Assert-Exit "Gateway lookup"
    $programmed = @($gateway.status.conditions | Where-Object { $_.type -eq "Programmed" -and $_.status -eq "True" })
    if ($programmed.Count -ne 1) { throw "Gateway is not Programmed=True." }
    $routes = (& kubectl get httproute -n minipay -o json) -join "`n" | ConvertFrom-Json
    Assert-Exit "HTTPRoute lookup"
    if ($routes.items.Count -ne $expectedRoutes) { throw "Expected $expectedRoutes HTTPRoutes, found $($routes.items.Count)." }
    foreach ($route in $routes.items) {
        $conditions = @($route.status.parents.conditions)
        if (-not ($conditions | Where-Object { $_.type -eq "Accepted" -and $_.status -eq "True" })) { throw "$($route.metadata.name) is not Accepted." }
        if (-not ($conditions | Where-Object { $_.type -eq "ResolvedRefs" -and $_.status -eq "True" })) { throw "$($route.metadata.name) has unresolved references." }
    }

    # The log scan also covers only running deployments: kubectl logs fails at zero replicas.
    foreach ($name in $running) {
        $logs = (& kubectl logs -n minipay "deployment/$name" --tail=200) -join "`n"
        if ($logs -match '(?i)(access denied|authentication failed|unknown database|communications link failure|failed to obtain jdbc|connection refused|invalid issuer|unable to resolve configuration)') {
            throw "$name logs contain a dependency/authentication/configuration failure."
        }
    }

    [void](New-Item -ItemType Directory -Path $evidenceDirectory -Force)
    [ordered]@{
        status = "technical-platform-passed"
        timestamp = [DateTimeOffset]::UtcNow.ToString("o")
        deployments = $results
        scaledToZero = @($results | Where-Object { $_.scaledToZero } | ForEach-Object { $_.name })
        gateway = "Programmed"
        routes = $expectedRoutes
        healthChecks = $healthChecked
        pending = @("business-flows", "consumer-h5-flows", "oauth-browser", "tcc-behavior", "rabbitmq-publish-consume", "pages", "data-recreate", "backup-restore")
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    Write-Host "[PASS] $($results.Count) Deployments in expected state ($($results.Count - $running.Count) intentionally scaled to zero), $healthChecked health checks, Gateway Programmed and $expectedRoutes HTTPRoutes." -ForegroundColor Green
    Write-Host "[INFO] Evidence: $evidencePath"
    Write-Host "[PENDING] Business, consumer-H5, browser OAuth, TCC, event, page and persistence gates are not implied by this pass."
}
catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "[HINT] Diagnose in order: Host -> Gateway/HTTPRoute -> Service -> EndpointSlice -> Pod -> probe -> ConfigMap/Secret -> dependency -> application log."
    exit 1
}
finally { Set-Location -LiteralPath $previousLocation }
