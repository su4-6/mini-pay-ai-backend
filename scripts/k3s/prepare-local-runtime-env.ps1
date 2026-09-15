# Generates deploy/k3s/overlays/local/runtime.env.
#
# Why this exists
# ---------------
# A fresh clone cannot run `kubectl apply -k deploy/k3s/overlays/local`:
# the overlay's configMapGenerator reads runtime.env, which is gitignored, and
# none of the three existing generators
#   (import-local-secrets / create-secrets / prepare-local-workload-secrets)
# writes it -- they only produce deploy/compose-infra/.env.local and
# overlays/local/private/*. Verified: in a clean checkout `kubectl kustomize
# deploy/k3s/overlays/local` fails with
#   loading KV pairs: env source files: [runtime.env]: evalsymlink failure
#
# The server equivalent is produced by scripts/k3s/bootstrap-server.ps1.
#
# Note on -InfraHost: the value must be the address that PODS use to reach the
# middleware, which is not the same as the address your host machine uses.
#   Docker Desktop (this project's dev setup): 192.168.65.254 (VM gateway)
#   Linux with middleware on the host:         host.docker.internal, or the
#                                              docker bridge IP (172.17.0.1)
#   Middleware inside the cluster:             its Service DNS name
[CmdletBinding()]
param(
    [string]$InfraHost = "192.168.65.254",
    [int]$MinipayMysqlPort = 13306,
    [int]$YshopMysqlPort = 13307,
    [int]$RedisPort = 16379,
    [int]$YshopRedisPort = 16380,
    [int]$RabbitmqPort = 15673,
    [string]$SeataServerAddr = "",
    [string]$GatewayPort = "18080",
    [string]$Domain = "minipay.localhost",
    [switch]$Force,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$overlayDirectory = Join-Path $repoRoot "deploy/k3s/overlays/local"
$targetPath = Join-Path $overlayDirectory "runtime.env"

if (-not (Test-Path -LiteralPath $overlayDirectory)) {
    throw "Local overlay not found: $overlayDirectory"
}
if ([string]::IsNullOrWhiteSpace($SeataServerAddr)) {
    $SeataServerAddr = "${InfraHost}:8091"
}
if ((Test-Path -LiteralPath $targetPath) -and -not $Force) {
    throw "$targetPath already exists. Re-run with -Force to overwrite. Nothing was changed."
}

# Must stay out of Git: it is environment-specific and drives a ConfigMap.
& git -C $repoRoot check-ignore --quiet -- "deploy/k3s/overlays/local/runtime.env" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "deploy/k3s/overlays/local/runtime.env must stay gitignored; refusing to generate."
}

$mysqlSuffix = "?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$base = "http://{0}.${Domain}:${GatewayPort}"

$values = [ordered]@{
    IDENTITY_MYSQL_URL                  = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_identity$mysqlSuffix"
    PAYMENT_MYSQL_URL                   = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_payment$mysqlSuffix"
    WALLET_MYSQL_URL                    = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_wallet$mysqlSuffix"
    COMMERCE_MYSQL_URL                  = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_commerce$mysqlSuffix"
    AGENT_MYSQL_URL                     = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_agent$mysqlSuffix"
    YSHOP_MYSQL_URL                     = "jdbc:mysql://${InfraHost}:${YshopMysqlPort}/yixiang_drink?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true"
    REDIS_HOST                          = $InfraHost
    REDIS_PORT                          = $RedisPort
    YSHOP_REDIS_HOST                    = $InfraHost
    YSHOP_REDIS_PORT                    = $YshopRedisPort
    RABBITMQ_HOST                       = $InfraHost
    RABBITMQ_PORT                       = $RabbitmqPort
    SEATA_SERVER_ADDR                   = $SeataServerAddr
    IDENTITY_PUBLIC_URL                 = ($base -f "identity")
    IDENTITY_ISSUER                     = ($base -f "identity")
    # Local access is plain HTTP through a port-forward, so the cookie must not
    # be Secure or the browser will drop it.
    SESSION_COOKIE_SECURE               = "false"
    MANAGEMENT_OAUTH_REDIRECT_URI       = (($base -f "ops") + "/login/oauth2/code/minipay-ops")
    ADMIN_OAUTH_REDIRECT_URI            = (($base -f "admin") + "/login/oauth2/code/minipay-admin")
    MERCHANT_BFF_OAUTH_REDIRECT_URI     = (($base -f "merchant") + "/merchant/oauth2/code")
    OPS_WEB_PUBLIC_URL                  = (($base -f "ops") + "/")
    ADMIN_WEB_PUBLIC_URL                = (($base -f "admin") + "/")
    MERCHANT_WEB_PUBLIC_URL             = (($base -f "merchant") + "/")
    MINIPAY_FOOD_H5_ORIGIN              = ($base -f "food")
    YSHOP_MINIPAY_H5_ORIGIN             = ($base -f "food")
    YSHOP_MINIPAY_ALLOW_GENERIC_ADDRESS = "true"
    # AMap web (JS) key for the food H5 map. yshop-server references it through a
    # configMapKeyRef, so it must exist locally as well or the pod fails with
    # CreateContainerConfigError.
    YSHOP_MINIPAY_AMAP_WEB_KEY          = "5998e7a69b3a589d2a9ee5126f289547"
}

$lines = @()
foreach ($key in $values.Keys) { $lines += "$key=$($values[$key])" }
# No BOM: kustomize cannot parse a BOM-prefixed env source.
[IO.File]::WriteAllLines($targetPath, $lines, [Text.UTF8Encoding]::new($false))
Write-Host "  wrote runtime.env ($($values.Count) keys) -> $targetPath"

if (-not $SkipVerify) {
    Push-Location $repoRoot
    try {
        $rendered = & kubectl kustomize deploy/k3s/overlays/local 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "kubectl kustomize deploy/k3s/overlays/local still fails:`n$(($rendered | Select-Object -First 3) -join "`n")"
        }
        Write-Host "  OK: overlays/local builds ($($rendered.Count) lines of YAML)" -ForegroundColor Green
    } finally {
        Pop-Location
    }
}
