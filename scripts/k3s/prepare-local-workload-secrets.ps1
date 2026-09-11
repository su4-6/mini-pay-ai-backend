[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "infra-common.ps1")

function New-RandomHex([int] $ByteCount = 32) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

function New-RandomBase64([int] $ByteCount = 32) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Assert-Keys([string] $Path, [string[]] $Keys) {
    $values = Read-InfraEnvironment $Path
    foreach ($key in $Keys) {
        if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$values[$key])) {
            throw "Private configuration is incomplete: $([IO.Path]::GetFileName($Path)) misses $key (value hidden)."
        }
    }
    return $values
}

function Write-PrivateEnvironment([string] $Path, [System.Collections.Specialized.OrderedDictionary] $Values) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        [void](Assert-Keys $Path @($Values.Keys))
        Write-Host "[PASS] Existing $([IO.Path]::GetFileName($Path)) preserved and validated." -ForegroundColor Green
        return
    }
    $lines = @($Values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
    [IO.File]::WriteAllLines($Path, $lines, [Text.UTF8Encoding]::new($false))
    [void](Assert-Keys $Path @($Values.Keys))
    Write-Host "[PASS] Created $([IO.Path]::GetFileName($Path)) (values hidden)." -ForegroundColor Green
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$privateDirectory = Join-Path $repositoryRoot "deploy/k3s/overlays/local/private"
$infraPath = Join-Path $repositoryRoot "deploy/compose-infra/.env.local"
$identityPath = Join-Path $privateDirectory "identity.env"

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "create-secrets.ps1") -Environment Local
if ($LASTEXITCODE -ne 0) { throw "Identity private configuration preparation failed." }

$infra = Read-InfraEnvironment $infraPath
$identity = Read-InfraEnvironment $identityPath
$requiredInfra = @(
    "PAYMENT_DB_USERNAME", "PAYMENT_DB_PASSWORD", "WALLET_DB_USERNAME", "WALLET_DB_PASSWORD",
    "COMMERCE_DB_USERNAME", "COMMERCE_DB_PASSWORD", "AGENT_DB_USERNAME", "AGENT_DB_PASSWORD",
    "MINIPAY_REDIS_PASSWORD", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
    "YSHOP_MYSQL_USERNAME", "YSHOP_MYSQL_PASSWORD", "YSHOP_REDIS_PASSWORD", "TURN_SHARED_SECRET"
)
$requiredIdentity = @(
    "PAYMENT_TO_IDENTITY_CLIENT_SECRET", "PAYMENT_TO_WALLET_CLIENT_SECRET",
    "WALLET_TO_IDENTITY_CLIENT_SECRET", "COMMERCE_TO_IDENTITY_CLIENT_SECRET",
    "AGENT_TO_IDENTITY_CLIENT_SECRET", "AGENT_DELEGATION_CLIENT_SECRET",
    "MANAGEMENT_OAUTH_CLIENT_SECRET", "ADMIN_OAUTH_CLIENT_SECRET"
)
foreach ($key in $requiredInfra) {
    if (-not $infra.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$infra[$key])) {
        throw "Compose private configuration misses $key (value hidden)."
    }
}
foreach ($key in $requiredIdentity) {
    if (-not $identity.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$identity[$key])) {
        throw "Identity private configuration misses $key (value hidden)."
    }
}

[void](New-Item -ItemType Directory -Path $privateDirectory -Force)

Write-PrivateEnvironment (Join-Path $privateDirectory "wallet.env") ([ordered]@{
    WALLET_MYSQL_USERNAME = $infra.WALLET_DB_USERNAME
    WALLET_MYSQL_PASSWORD = $infra.WALLET_DB_PASSWORD
    RABBITMQ_USERNAME = $infra.RABBITMQ_USERNAME
    RABBITMQ_PASSWORD = $infra.RABBITMQ_PASSWORD
    WALLET_TO_IDENTITY_CLIENT_SECRET = $identity.WALLET_TO_IDENTITY_CLIENT_SECRET
})

Write-PrivateEnvironment (Join-Path $privateDirectory "payment.env") ([ordered]@{
    PAYMENT_MYSQL_USERNAME = $infra.PAYMENT_DB_USERNAME
    PAYMENT_MYSQL_PASSWORD = $infra.PAYMENT_DB_PASSWORD
    RABBITMQ_USERNAME = $infra.RABBITMQ_USERNAME
    RABBITMQ_PASSWORD = $infra.RABBITMQ_PASSWORD
    PAYMENT_TO_IDENTITY_CLIENT_SECRET = $identity.PAYMENT_TO_IDENTITY_CLIENT_SECRET
    PAYMENT_TO_WALLET_CLIENT_SECRET = $identity.PAYMENT_TO_WALLET_CLIENT_SECRET
    BANK_SANDBOX_TOKENIZATION_KEY = (New-RandomHex)
    COLLECTION_CODE_SIGNING_KEY = (New-RandomHex)
    MERCHANT_APP_SECRET_KEY = (New-RandomBase64)
})

$commercePath = Join-Path $privateDirectory "commerce.env"
$yshopPath = Join-Path $privateDirectory "yshop.env"
$sharedHmac = (New-RandomHex)
if (Test-Path -LiteralPath $commercePath -PathType Leaf) {
    $sharedHmac = (Read-InfraEnvironment $commercePath).YSHOP_MINIPAY_HMAC_SECRET
} elseif (Test-Path -LiteralPath $yshopPath -PathType Leaf) {
    $sharedHmac = (Read-InfraEnvironment $yshopPath).YSHOP_MINIPAY_HMAC_SECRET
}
if ([string]::IsNullOrWhiteSpace([string]$sharedHmac)) { throw "Existing YShop HMAC configuration is empty." }

Write-PrivateEnvironment $commercePath ([ordered]@{
    MYSQL_USERNAME = $infra.COMMERCE_DB_USERNAME
    MYSQL_PASSWORD = $infra.COMMERCE_DB_PASSWORD
    RABBITMQ_USERNAME = $infra.RABBITMQ_USERNAME
    RABBITMQ_PASSWORD = $infra.RABBITMQ_PASSWORD
    YSHOP_MINIPAY_HMAC_SECRET = $sharedHmac
    COMMERCE_TO_IDENTITY_CLIENT_SECRET = $identity.COMMERCE_TO_IDENTITY_CLIENT_SECRET
    COMMERCE_ADDRESS_ENCRYPTION_KEY = (New-RandomBase64)
})

Write-PrivateEnvironment (Join-Path $privateDirectory "agent.env") ([ordered]@{
    MYSQL_USERNAME = $infra.AGENT_DB_USERNAME
    MYSQL_PASSWORD = $infra.AGENT_DB_PASSWORD
    REDIS_PASSWORD = $infra.MINIPAY_REDIS_PASSWORD
    RABBITMQ_USERNAME = $infra.RABBITMQ_USERNAME
    RABBITMQ_PASSWORD = $infra.RABBITMQ_PASSWORD
    AGENT_TO_IDENTITY_CLIENT_SECRET = $identity.AGENT_TO_IDENTITY_CLIENT_SECRET
    AGENT_DELEGATION_CLIENT_SECRET = $identity.AGENT_DELEGATION_CLIENT_SECRET
    TURN_SHARED_SECRET = $infra.TURN_SHARED_SECRET
})

Write-PrivateEnvironment (Join-Path $privateDirectory "consumer-bff.env") ([ordered]@{
    REDIS_PASSWORD = $infra.MINIPAY_REDIS_PASSWORD
})
Write-PrivateEnvironment (Join-Path $privateDirectory "management-bff.env") ([ordered]@{
    REDIS_PASSWORD = $infra.MINIPAY_REDIS_PASSWORD
    MANAGEMENT_OAUTH_CLIENT_SECRET = $identity.MANAGEMENT_OAUTH_CLIENT_SECRET
})
Write-PrivateEnvironment (Join-Path $privateDirectory "admin-bff.env") ([ordered]@{
    REDIS_PASSWORD = $infra.MINIPAY_REDIS_PASSWORD
    ADMIN_OAUTH_CLIENT_SECRET = $identity.ADMIN_OAUTH_CLIENT_SECRET
})
Write-PrivateEnvironment $yshopPath ([ordered]@{
    SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_USERNAME = $infra.YSHOP_MYSQL_USERNAME
    SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD = $infra.YSHOP_MYSQL_PASSWORD
    SPRING_DATA_REDIS_PASSWORD = $infra.YSHOP_REDIS_PASSWORD
    YSHOP_MINIPAY_HMAC_SECRET = $sharedHmac
})

$wallet = Read-InfraEnvironment (Join-Path $privateDirectory "wallet.env")
$payment = Read-InfraEnvironment (Join-Path $privateDirectory "payment.env")
$commerce = Read-InfraEnvironment $commercePath
$agent = Read-InfraEnvironment (Join-Path $privateDirectory "agent.env")
$management = Read-InfraEnvironment (Join-Path $privateDirectory "management-bff.env")
$admin = Read-InfraEnvironment (Join-Path $privateDirectory "admin-bff.env")
$yshop = Read-InfraEnvironment $yshopPath
$pairs = @(
    @($wallet.WALLET_MYSQL_USERNAME, $infra.WALLET_DB_USERNAME, "Wallet database username"),
    @($wallet.WALLET_MYSQL_PASSWORD, $infra.WALLET_DB_PASSWORD, "Wallet database password"),
    @($wallet.WALLET_TO_IDENTITY_CLIENT_SECRET, $identity.WALLET_TO_IDENTITY_CLIENT_SECRET, "Wallet to Identity client secret"),
    @($payment.PAYMENT_MYSQL_USERNAME, $infra.PAYMENT_DB_USERNAME, "Payment database username"),
    @($payment.PAYMENT_MYSQL_PASSWORD, $infra.PAYMENT_DB_PASSWORD, "Payment database password"),
    @($payment.PAYMENT_TO_IDENTITY_CLIENT_SECRET, $identity.PAYMENT_TO_IDENTITY_CLIENT_SECRET, "Payment to Identity client secret"),
    @($payment.PAYMENT_TO_WALLET_CLIENT_SECRET, $identity.PAYMENT_TO_WALLET_CLIENT_SECRET, "Payment to Wallet client secret"),
    @($commerce.MYSQL_USERNAME, $infra.COMMERCE_DB_USERNAME, "Commerce database username"),
    @($commerce.MYSQL_PASSWORD, $infra.COMMERCE_DB_PASSWORD, "Commerce database password"),
    @($commerce.COMMERCE_TO_IDENTITY_CLIENT_SECRET, $identity.COMMERCE_TO_IDENTITY_CLIENT_SECRET, "Commerce to Identity client secret"),
    @($agent.MYSQL_USERNAME, $infra.AGENT_DB_USERNAME, "Agent database username"),
    @($agent.MYSQL_PASSWORD, $infra.AGENT_DB_PASSWORD, "Agent database password"),
    @($agent.AGENT_TO_IDENTITY_CLIENT_SECRET, $identity.AGENT_TO_IDENTITY_CLIENT_SECRET, "Agent to Identity client secret"),
    @($agent.AGENT_DELEGATION_CLIENT_SECRET, $identity.AGENT_DELEGATION_CLIENT_SECRET, "Agent delegation client secret"),
    @($management.MANAGEMENT_OAUTH_CLIENT_SECRET, $identity.MANAGEMENT_OAUTH_CLIENT_SECRET, "Management OAuth client secret"),
    @($admin.ADMIN_OAUTH_CLIENT_SECRET, $identity.ADMIN_OAUTH_CLIENT_SECRET, "Admin OAuth client secret"),
    @($yshop.SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_USERNAME, $infra.YSHOP_MYSQL_USERNAME, "YShop database username"),
    @($yshop.SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD, $infra.YSHOP_MYSQL_PASSWORD, "YShop database password")
)
foreach ($pair in $pairs) {
    if ($pair[0] -cne $pair[1]) {
        throw "$($pair[2]) differs across its two private sources. Existing files were preserved; rotate them as one planned change."
    }
}
if ($commerce.YSHOP_MINIPAY_HMAC_SECRET -ne $yshop.YSHOP_MINIPAY_HMAC_SECRET) {
    throw "Commerce and YShop HMAC secrets differ. Existing files were preserved; rotate them as one planned change."
}

Write-Host "[PASS] All local workload private files are complete and cross-service pairs match." -ForegroundColor Green
Write-Host "[INFO] Values were not printed or applied to Kubernetes."
