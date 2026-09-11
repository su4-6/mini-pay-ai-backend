# Generates every server-side file that `deploy/k3s/overlays/server` needs but that
# is deliberately kept out of Git: runtime.env, the nine per-workload private env
# files, the JWT signing key pair, and the image digest component.
#
# Why this exists
# ---------------
# overlays/server cannot be applied on a fresh clone. `kubectl kustomize` fails on
# the missing runtime.env, and the secretGenerator entries point at
# private/*.env + private/jwt-*.pem which are gitignored. The three existing
# generator scripts (import-local-secrets / create-secrets /
# prepare-local-workload-secrets) are all hardcoded to overlays/local, so there was
# no supported way to prepare a server.
#
# Cross-file consistency is the whole point of doing this in one script: several
# secrets are shared, and generating them independently per file silently breaks
# OAuth (issuer and client would hold different values).
#
#   shared across files:
#     RABBITMQ_USERNAME / RABBITMQ_PASSWORD     6 workloads
#     REDIS_PASSWORD                            5 workloads
#     YSHOP_MINIPAY_HMAC_SECRET                 commerce + yshop
#     each *_CLIENT_SECRET                      identity + its counterpart
#
# Crypto caution: if you restore an EXISTING database onto the server, the
# identity peppers (PHONE_HASH_PEPPER / EMAIL_HASH_PEPPER / TOKEN_DIGEST_PEPPER /
# REAL_NAME_HMAC_KEY) and the encryption keys must be the SAME as the ones that
# produced that data, otherwise every stored hash stops matching. Pass
# -ReuseCryptoFrom <path to a local overlays/local/private> in that case.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InfraHost,
    [string]$Domain = "su46proj.site",
    [int]$MinipayMysqlPort = 3306,
    [int]$YshopMysqlPort = 3307,
    [int]$RedisPort = 6379,
    [int]$YshopRedisPort = 6380,
    [int]$RabbitmqPort = 5672,
    [string]$SeataServerAddr = "",
    [string]$ReuseCryptoFrom = "",
    [switch]$Force,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$overlayDirectory = Join-Path $repoRoot "deploy/k3s/overlays/server"
$privateDirectory = Join-Path $overlayDirectory "private"
$digestRecord = Join-Path $repoRoot "deploy/k3s/generated/image-digests.json"

if (-not (Test-Path -LiteralPath $overlayDirectory)) {
    throw "Server overlay not found: $overlayDirectory"
}
if ([string]::IsNullOrWhiteSpace($SeataServerAddr)) {
    $SeataServerAddr = "${InfraHost}:8091"
}

function New-HexSecret {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
}

function New-Base64Key {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

# Native tools write progress to stderr ("writing RSA key"). With
# $ErrorActionPreference = "Stop" PowerShell turns that into a terminating
# NativeCommandError even when stderr is redirected, so native calls are wrapped.
function Invoke-Native {
    param([Parameter(Mandatory)][string]$Command, [Parameter(Mandatory)][string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Command @Arguments 2>&1 | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Write-EnvFile {
    param([string]$Path, [System.Collections.Specialized.OrderedDictionary]$Values)
    $lines = @()
    foreach ($key in $Values.Keys) {
        $lines += "$key=$($Values[$key])"
    }
    # No BOM: kustomize env sources must be plain UTF-8 (a BOM corrupts the first key).
    [IO.File]::WriteAllLines($Path, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host "    wrote $([IO.Path]::GetFileName($Path)) ($($Values.Count) keys)"
}

# Refuse to clobber without -Force, and make sure the target stays out of Git.
$existing = @()
if (Test-Path -LiteralPath $privateDirectory) {
    $existing += Get-ChildItem -LiteralPath $privateDirectory -File -ErrorAction SilentlyContinue
}
if (Test-Path -LiteralPath (Join-Path $overlayDirectory "runtime.env")) {
    $existing += Get-Item -LiteralPath (Join-Path $overlayDirectory "runtime.env")
}
$existing = $existing | Where-Object { $_.Name -ne "kustomization.yaml" }
if ($existing.Count -gt 0 -and -not $Force) {
    throw "Server private material already exists ($($existing.Count) files). Re-run with -Force to regenerate, or delete $privateDirectory manually. Nothing was overwritten."
}

& git -C $repoRoot check-ignore --quiet -- "deploy/k3s/overlays/server/runtime.env" 2>$null
if ($LASTEXITCODE -ne 0) { throw "deploy/k3s/overlays/server/runtime.env must stay gitignored; refusing to generate." }
& git -C $repoRoot check-ignore --quiet -- "deploy/k3s/overlays/server/private/identity.env" 2>$null
if ($LASTEXITCODE -ne 0) { throw "deploy/k3s/overlays/server/private/ must stay gitignored; refusing to generate." }

New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null

Write-Host "==> Generating runtime.env (domain=$Domain infra=$InfraHost)"
$mysqlSuffix = "?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$runtime = [ordered]@{
    IDENTITY_MYSQL_URL                  = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_identity$mysqlSuffix"
    PAYMENT_MYSQL_URL                   = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_payment$mysqlSuffix"
    WALLET_MYSQL_URL                    = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_wallet$mysqlSuffix"
    COMMERCE_MYSQL_URL                  = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_commerce$mysqlSuffix"
    AGENT_MYSQL_URL                     = "jdbc:mysql://${InfraHost}:${MinipayMysqlPort}/minipay_agent$mysqlSuffix"
    YSHOP_MYSQL_URL                     = "jdbc:mysql://${InfraHost}:${YshopMysqlPort}/yixiang_drink?useSSL=true&serverTimezone=UTC&nullCatalogMeansCurrent=true"
    REDIS_HOST                          = $InfraHost
    REDIS_PORT                          = $RedisPort
    YSHOP_REDIS_HOST                    = $InfraHost
    YSHOP_REDIS_PORT                    = $YshopRedisPort
    RABBITMQ_HOST                       = $InfraHost
    RABBITMQ_PORT                       = $RabbitmqPort
    SEATA_SERVER_ADDR                   = $SeataServerAddr
    IDENTITY_PUBLIC_URL                 = "https://identity.$Domain"
    IDENTITY_ISSUER                     = "https://identity.$Domain"
    # Real TLS terminates at the gateway, so the session cookie must be Secure.
    SESSION_COOKIE_SECURE               = "true"
    MANAGEMENT_OAUTH_REDIRECT_URI       = "https://ops.$Domain/login/oauth2/code/minipay-ops"
    ADMIN_OAUTH_REDIRECT_URI            = "https://admin.$Domain/login/oauth2/code/minipay-admin"
    MERCHANT_BFF_OAUTH_REDIRECT_URI     = "https://merchant.$Domain/merchant/oauth2/code"
    OPS_WEB_PUBLIC_URL                  = "https://ops.$Domain/"
    ADMIN_WEB_PUBLIC_URL                = "https://admin.$Domain/"
    MERCHANT_WEB_PUBLIC_URL             = "https://merchant.$Domain/"
    MINIPAY_FOOD_H5_ORIGIN              = "https://food.$Domain"
    YSHOP_MINIPAY_H5_ORIGIN             = "https://food.$Domain"
    YSHOP_MINIPAY_ALLOW_GENERIC_ADDRESS = "true"
}
Write-EnvFile (Join-Path $overlayDirectory "runtime.env") $runtime

Write-Host "==> Generating shared secrets (kept identical across files)"
$rabbitUser = "minipay"
$rabbitPassword = New-HexSecret
$redisPassword = New-HexSecret
$yshopRedisPassword = New-HexSecret
$yshopHmac = New-HexSecret
$oauth = [ordered]@{
    MANAGEMENT_OAUTH_CLIENT_SECRET        = New-HexSecret
    ADMIN_OAUTH_CLIENT_SECRET             = New-HexSecret
    PAYMENT_TO_IDENTITY_CLIENT_SECRET     = New-HexSecret
    PAYMENT_TO_WALLET_CLIENT_SECRET       = New-HexSecret
    WALLET_TO_IDENTITY_CLIENT_SECRET      = New-HexSecret
    AGENT_TO_IDENTITY_CLIENT_SECRET       = New-HexSecret
    AGENT_DELEGATION_CLIENT_SECRET        = New-HexSecret
    COMMERCE_TO_IDENTITY_CLIENT_SECRET    = New-HexSecret
    AGENT_TO_PAYMENT_CLIENT_SECRET        = New-HexSecret
    IDENTITY_TO_PAYMENT_CLIENT_SECRET     = New-HexSecret
}
$mysqlPassword = [ordered]@{
    identity = New-HexSecret
    agent    = New-HexSecret
    commerce = New-HexSecret
    payment  = New-HexSecret
    wallet   = New-HexSecret
    yshop    = New-HexSecret
}

# Crypto material. Either freshly generated (fresh database) or copied from an
# existing environment (restored database) -- see the caution at the top.
$cryptoKeys = @(
    "PHONE_HASH_PEPPER", "PHONE_DISCLOSURE_ENCRYPTION_KEY", "PHONE_DISCLOSURE_KEY_ID",
    "EMAIL_HASH_PEPPER", "TOKEN_DIGEST_PEPPER", "PAYMENT_AUTHORIZATION_TOKEN_KEY",
    "REAL_NAME_HMAC_KEY", "AUTH_AUDIT_PEPPER", "CAPTCHA_PEPPER",
    "COMMERCE_ADDRESS_ENCRYPTION_KEY", "BANK_SANDBOX_TOKENIZATION_KEY",
    "COLLECTION_CODE_SIGNING_KEY", "MERCHANT_APP_SECRET_KEY", "TURN_SHARED_SECRET"
)
$crypto = [ordered]@{}
if (-not [string]::IsNullOrWhiteSpace($ReuseCryptoFrom)) {
    Write-Host "    reusing crypto material from $ReuseCryptoFrom"
    if (-not (Test-Path -LiteralPath $ReuseCryptoFrom)) { throw "ReuseCryptoFrom path not found: $ReuseCryptoFrom" }
    foreach ($key in $cryptoKeys) {
        $found = $null
        Get-ChildItem -LiteralPath $ReuseCryptoFrom -File -Filter "*.env" | ForEach-Object {
            foreach ($line in [IO.File]::ReadAllLines($_.FullName)) {
                if ($line -match "^$([regex]::Escape($key))=(.*)$") { $found = $matches[1] }
            }
        }
        if (-not $found) { throw "ReuseCryptoFrom is missing $key" }
        $crypto[$key] = $found
    }
} else {
    foreach ($key in $cryptoKeys) {
        # Two keys are consumed as raw 32-byte AES material, so they use base64.
        if ($key -in @("COMMERCE_ADDRESS_ENCRYPTION_KEY", "MERCHANT_APP_SECRET_KEY")) {
            $crypto[$key] = New-Base64Key
        } elseif ($key -eq "PHONE_DISCLOSURE_KEY_ID") {
            $crypto[$key] = "minipay-v1"
        } else {
            $crypto[$key] = New-HexSecret
        }
    }
}

Write-Host "==> Generating per-workload private env files"
Write-EnvFile (Join-Path $privateDirectory "identity.env") ([ordered]@{
    MYSQL_USERNAME                      = "minipay_identity_app"
    MYSQL_PASSWORD                      = $mysqlPassword.identity
    REDIS_PASSWORD                      = $redisPassword
    RABBITMQ_USERNAME                   = $rabbitUser
    RABBITMQ_PASSWORD                   = $rabbitPassword
    PHONE_HASH_PEPPER                   = $crypto.PHONE_HASH_PEPPER
    PHONE_DISCLOSURE_ENCRYPTION_KEY     = $crypto.PHONE_DISCLOSURE_ENCRYPTION_KEY
    PHONE_DISCLOSURE_KEY_ID             = $crypto.PHONE_DISCLOSURE_KEY_ID
    EMAIL_HASH_PEPPER                   = $crypto.EMAIL_HASH_PEPPER
    TOKEN_DIGEST_PEPPER                 = $crypto.TOKEN_DIGEST_PEPPER
    PAYMENT_AUTHORIZATION_TOKEN_KEY     = $crypto.PAYMENT_AUTHORIZATION_TOKEN_KEY
    REAL_NAME_HMAC_KEY                  = $crypto.REAL_NAME_HMAC_KEY
    AUTH_AUDIT_PEPPER                   = $crypto.AUTH_AUDIT_PEPPER
    CAPTCHA_PEPPER                      = $crypto.CAPTCHA_PEPPER
    MANAGEMENT_OAUTH_CLIENT_SECRET      = $oauth.MANAGEMENT_OAUTH_CLIENT_SECRET
    ADMIN_OAUTH_CLIENT_SECRET           = $oauth.ADMIN_OAUTH_CLIENT_SECRET
    PAYMENT_TO_IDENTITY_CLIENT_SECRET   = $oauth.PAYMENT_TO_IDENTITY_CLIENT_SECRET
    PAYMENT_TO_WALLET_CLIENT_SECRET     = $oauth.PAYMENT_TO_WALLET_CLIENT_SECRET
    WALLET_TO_IDENTITY_CLIENT_SECRET    = $oauth.WALLET_TO_IDENTITY_CLIENT_SECRET
    AGENT_TO_PAYMENT_CLIENT_SECRET      = $oauth.AGENT_TO_PAYMENT_CLIENT_SECRET
    AGENT_TO_IDENTITY_CLIENT_SECRET     = $oauth.AGENT_TO_IDENTITY_CLIENT_SECRET
    AGENT_DELEGATION_CLIENT_SECRET      = $oauth.AGENT_DELEGATION_CLIENT_SECRET
    IDENTITY_TO_PAYMENT_CLIENT_SECRET   = $oauth.IDENTITY_TO_PAYMENT_CLIENT_SECRET
    COMMERCE_TO_IDENTITY_CLIENT_SECRET  = $oauth.COMMERCE_TO_IDENTITY_CLIENT_SECRET
})
Write-EnvFile (Join-Path $privateDirectory "wallet.env") ([ordered]@{
    WALLET_MYSQL_USERNAME            = "minipay_wallet_app"
    WALLET_MYSQL_PASSWORD            = $mysqlPassword.wallet
    RABBITMQ_USERNAME                = $rabbitUser
    RABBITMQ_PASSWORD                = $rabbitPassword
    WALLET_TO_IDENTITY_CLIENT_SECRET = $oauth.WALLET_TO_IDENTITY_CLIENT_SECRET
})
Write-EnvFile (Join-Path $privateDirectory "payment.env") ([ordered]@{
    PAYMENT_MYSQL_USERNAME              = "minipay_payment_app"
    PAYMENT_MYSQL_PASSWORD              = $mysqlPassword.payment
    RABBITMQ_USERNAME                   = $rabbitUser
    RABBITMQ_PASSWORD                   = $rabbitPassword
    PAYMENT_TO_IDENTITY_CLIENT_SECRET   = $oauth.PAYMENT_TO_IDENTITY_CLIENT_SECRET
    PAYMENT_TO_WALLET_CLIENT_SECRET     = $oauth.PAYMENT_TO_WALLET_CLIENT_SECRET
    BANK_SANDBOX_TOKENIZATION_KEY       = $crypto.BANK_SANDBOX_TOKENIZATION_KEY
    COLLECTION_CODE_SIGNING_KEY         = $crypto.COLLECTION_CODE_SIGNING_KEY
    MERCHANT_APP_SECRET_KEY             = $crypto.MERCHANT_APP_SECRET_KEY
})
Write-EnvFile (Join-Path $privateDirectory "commerce.env") ([ordered]@{
    MYSQL_USERNAME                     = "minipay_commerce_app"
    MYSQL_PASSWORD                     = $mysqlPassword.commerce
    RABBITMQ_USERNAME                  = $rabbitUser
    RABBITMQ_PASSWORD                  = $rabbitPassword
    YSHOP_MINIPAY_HMAC_SECRET          = $yshopHmac
    COMMERCE_TO_IDENTITY_CLIENT_SECRET = $oauth.COMMERCE_TO_IDENTITY_CLIENT_SECRET
    COMMERCE_ADDRESS_ENCRYPTION_KEY    = $crypto.COMMERCE_ADDRESS_ENCRYPTION_KEY
})
Write-EnvFile (Join-Path $privateDirectory "agent.env") ([ordered]@{
    MYSQL_USERNAME                  = "minipay_agent_app"
    MYSQL_PASSWORD                  = $mysqlPassword.agent
    REDIS_PASSWORD                  = $redisPassword
    RABBITMQ_USERNAME               = $rabbitUser
    RABBITMQ_PASSWORD               = $rabbitPassword
    AGENT_TO_IDENTITY_CLIENT_SECRET = $oauth.AGENT_TO_IDENTITY_CLIENT_SECRET
    AGENT_DELEGATION_CLIENT_SECRET  = $oauth.AGENT_DELEGATION_CLIENT_SECRET
    TURN_SHARED_SECRET              = $crypto.TURN_SHARED_SECRET
})
Write-EnvFile (Join-Path $privateDirectory "consumer-bff.env") ([ordered]@{
    REDIS_PASSWORD = $redisPassword
})
Write-EnvFile (Join-Path $privateDirectory "management-bff.env") ([ordered]@{
    REDIS_PASSWORD                 = $redisPassword
    MANAGEMENT_OAUTH_CLIENT_SECRET = $oauth.MANAGEMENT_OAUTH_CLIENT_SECRET
})
Write-EnvFile (Join-Path $privateDirectory "admin-bff.env") ([ordered]@{
    REDIS_PASSWORD            = $redisPassword
    ADMIN_OAUTH_CLIENT_SECRET = $oauth.ADMIN_OAUTH_CLIENT_SECRET
})
Write-EnvFile (Join-Path $privateDirectory "yshop.env") ([ordered]@{
    SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_USERNAME = "yshop"
    SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD = $mysqlPassword.yshop
    SPRING_DATA_REDIS_PASSWORD                           = $yshopRedisPassword
    SPRING_RABBITMQ_USERNAME                             = $rabbitUser
    SPRING_RABBITMQ_PASSWORD                             = $rabbitPassword
    YSHOP_MINIPAY_HMAC_SECRET                            = $yshopHmac
})

Write-Host "==> Generating the JWT signing key pair (PKCS#8 + SPKI, RSA-2048)"
$privateKeyPath = Join-Path $privateDirectory "jwt-private.pem"
$publicKeyPath = Join-Path $privateDirectory "jwt-public.pem"
$keyCode = Invoke-Native "openssl" @("genpkey", "-quiet", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", $privateKeyPath)
if ($keyCode -ne 0 -or -not (Test-Path -LiteralPath $privateKeyPath)) {
    throw "openssl failed to generate the private key (exit $keyCode). Install openssl and retry."
}
$pubCode = Invoke-Native "openssl" @("rsa", "-in", $privateKeyPath, "-pubout", "-out", $publicKeyPath)
if ($pubCode -ne 0 -or -not (Test-Path -LiteralPath $publicKeyPath)) {
    throw "openssl failed to derive the public key (exit $pubCode)."
}
Write-Host "    wrote jwt-private.pem / jwt-public.pem"

Write-Host "==> Generating the image digest component from deploy/k3s/generated/image-digests.json"
if (-not (Test-Path -LiteralPath $digestRecord)) {
    Write-Warning "Digest record missing ($digestRecord). Run scripts/k3s/push-and-record-digests.ps1 first; the digest component was left untouched."
} else {
    $record = Get-Content -LiteralPath $digestRecord -Raw | ConvertFrom-Json
    $entries = @()
    foreach ($property in $record.digests.PSObject.Properties) {
        # Keys look like "suqihang/identity-service:0.1.0-k3s.1"
        $image = ($property.Name -split ":")[0]
        $entries += "  - name: $image"
        $entries += "    newName: $image"
        $entries += "    digest: $($property.Value)"
    }
    $component = @(
        "apiVersion: kustomize.config.k8s.io/v1alpha1",
        "kind: Component",
        "images:"
    ) + $entries
    $digestDirectory = Join-Path $privateDirectory "digests"
    New-Item -ItemType Directory -Path $digestDirectory -Force | Out-Null
    # No BOM: kustomize cannot parse a BOM-prefixed YAML file.
    [IO.File]::WriteAllLines((Join-Path $digestDirectory "kustomization.yaml"), $component, [Text.UTF8Encoding]::new($false))
    Write-Host "    wrote private/digests/kustomization.yaml ($(@($record.digests.PSObject.Properties).Count) images)"
}

if (-not $SkipVerify) {
    Write-Host "==> Verifying that the server overlay now builds"
    Push-Location $repoRoot
    try {
        $rendered = & kubectl kustomize deploy/k3s/overlays/server 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "kubectl kustomize deploy/k3s/overlays/server failed:`n$(($rendered | Select-Object -First 5) -join "`n")"
        }
        Write-Host "    OK ($($rendered.Count) lines of YAML)" -ForegroundColor Green
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "Server material is ready. Next:" -ForegroundColor Green
Write-Host "  1. Create the databases and let Flyway migrate them."
Write-Host "  2. kubectl create secret docker-registry dockerhub-pull --docker-server=docker.io --docker-username=<user> --docker-password=<token> -n minipay"
Write-Host "  3. kubectl apply -k deploy/k3s/overlays/server"
Write-Host ""
Write-Host "Reminder: deploy/k3s/overlays/server/private/ and runtime.env stay out of Git. Back them up somewhere safe."
