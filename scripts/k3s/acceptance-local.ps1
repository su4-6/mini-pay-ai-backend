[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
function Assert-Exit([string] $Message) { if ($LASTEXITCODE -ne 0) { throw "$Message failed (exit $LASTEXITCODE)." } }
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$evidenceDirectory = Join-Path $repositoryRoot "deploy/k3s/generated"
$evidencePath = Join-Path $evidenceDirectory "local-platform.json"
$deployments = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "agent-service",
    "consumer-bff", "management-bff", "admin-bff", "merchant-web", "ops-web", "admin-web",
    "yshop-server", "yshop-food-h5", "yshop-admin-web"
)
$javaServices = @(
    "identity-service", "wallet-service", "payment-service", "commerce-service", "agent-service",
    "consumer-bff", "management-bff", "admin-bff"
)
$webServices = @("merchant-web", "ops-web", "admin-web", "yshop-food-h5", "yshop-admin-web")
$previousLocation = Get-Location

try {
    Set-Location -LiteralPath $repositoryRoot
    $results = @()
    foreach ($name in $deployments) {
        $deployment = (& kubectl get deployment $name -n minipay -o json) -join "`n" | ConvertFrom-Json
        Assert-Exit "Deployment lookup for $name"
        if ($deployment.status.readyReplicas -ne $deployment.spec.replicas) { throw "$name is not fully Ready." }
        $slices = (& kubectl get endpointslice -n minipay -l "kubernetes.io/service-name=$name" -o json) -join "`n" | ConvertFrom-Json
        Assert-Exit "EndpointSlice lookup for $name"
        $ready = @($slices.items.endpoints | Where-Object { $_.conditions.ready -eq $true })
        if ($ready.Count -lt 1) { throw "$name has no Ready EndpointSlice endpoint." }
        $results += [ordered]@{name = $name; readyReplicas = $deployment.status.readyReplicas; readyEndpoints = $ready.Count; image = $deployment.spec.template.spec.containers[0].image}
    }

    foreach ($name in $javaServices) {
        $health = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:$name`:8080/proxy/actuator/health/readiness") -join ""
        Assert-Exit "Readiness request for $name"
        if ($health -notmatch '"status"\s*:\s*"UP"') { throw "$name readiness did not report UP." }
    }
    $yshopHealth = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:yshop-server`:48080/proxy/actuator/health") -join ""
    Assert-Exit "YShop readiness request"
    if ($yshopHealth -notmatch '"status"\s*:\s*"UP"') { throw "YShop health did not report UP." }
    foreach ($name in $webServices) {
        $health = (& kubectl get --raw "/api/v1/namespaces/minipay/services/http:$name`:80/proxy/healthz") -join ""
        Assert-Exit "Static health request for $name"
        if ($health.Trim() -ne "ok") { throw "$name /healthz did not return ok." }
    }

    $gateway = (& kubectl get gateway minipay-gateway -n minipay -o json) -join "`n" | ConvertFrom-Json
    Assert-Exit "Gateway lookup"
    $programmed = @($gateway.status.conditions | Where-Object { $_.type -eq "Programmed" -and $_.status -eq "True" })
    if ($programmed.Count -ne 1) { throw "Gateway is not Programmed=True." }
    $routes = (& kubectl get httproute -n minipay -o json) -join "`n" | ConvertFrom-Json
    Assert-Exit "HTTPRoute lookup"
    if ($routes.items.Count -ne 10) { throw "Expected 10 HTTPRoutes, found $($routes.items.Count)." }
    foreach ($route in $routes.items) {
        $conditions = @($route.status.parents.conditions)
        if (-not ($conditions | Where-Object { $_.type -eq "Accepted" -and $_.status -eq "True" })) { throw "$($route.metadata.name) is not Accepted." }
        if (-not ($conditions | Where-Object { $_.type -eq "ResolvedRefs" -and $_.status -eq "True" })) { throw "$($route.metadata.name) has unresolved references." }
    }

    foreach ($name in $deployments) {
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
        gateway = "Programmed"
        routes = 10
        healthChecks = 14
        pending = @("business-flows", "oauth-browser", "tcc-behavior", "rabbitmq-publish-consume", "pages", "android", "data-recreate", "backup-restore")
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    Write-Host "[PASS] 14 Ready Deployments, 14 Ready Services, health checks, Gateway and 10 HTTPRoutes." -ForegroundColor Green
    Write-Host "[INFO] Evidence: $evidencePath"
    Write-Host "[PENDING] Business, browser OAuth, TCC, event, page, Android and persistence gates are not implied by this pass."
}
catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "[HINT] Diagnose in order: Host -> Gateway/HTTPRoute -> Service -> EndpointSlice -> Pod -> probe -> ConfigMap/Secret -> dependency -> application log."
    exit 1
}
finally { Set-Location -LiteralPath $previousLocation }
