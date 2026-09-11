[CmdletBinding()]
param(
    [ValidateSet("Local")]
    [string] $Environment = "Local"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "infra-common.ps1")

function New-RandomHex([int] $ByteCount = 32) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

function Assert-IdentityEnvironment([hashtable] $Values) {
    $required = @(
        "MYSQL_USERNAME", "MYSQL_PASSWORD", "REDIS_PASSWORD",
        "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
        "PHONE_HASH_PEPPER", "PHONE_DISCLOSURE_ENCRYPTION_KEY",
        "EMAIL_HASH_PEPPER", "TOKEN_DIGEST_PEPPER",
        "PAYMENT_AUTHORIZATION_TOKEN_KEY", "REAL_NAME_HMAC_KEY",
        "AUTH_AUDIT_PEPPER", "CAPTCHA_PEPPER",
        "MANAGEMENT_OAUTH_CLIENT_SECRET", "ADMIN_OAUTH_CLIENT_SECRET",
        "PAYMENT_TO_IDENTITY_CLIENT_SECRET", "PAYMENT_TO_WALLET_CLIENT_SECRET",
        "WALLET_TO_IDENTITY_CLIENT_SECRET", "AGENT_TO_PAYMENT_CLIENT_SECRET",
        "AGENT_TO_IDENTITY_CLIENT_SECRET", "AGENT_DELEGATION_CLIENT_SECRET",
        "IDENTITY_TO_PAYMENT_CLIENT_SECRET", "COMMERCE_TO_IDENTITY_CLIENT_SECRET"
    )
    foreach ($key in $required) {
        if (-not $Values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($Values[$key])) {
            throw "Identity private configuration is missing $key (value hidden)."
        }
        if ($key -match "PASSWORD|SECRET|KEY|PEPPER" -and ([string]$Values[$key]).Length -lt 16) {
            throw "Identity private configuration contains a short secret: $key (value hidden)."
        }
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$infraPath = Join-Path $repositoryRoot "deploy/compose-infra/.env.local"
$privateDirectory = Join-Path $repositoryRoot "deploy/k3s/overlays/local/private"
$identityEnvironmentPath = Join-Path $privateDirectory "identity.env"
$privateKeyPath = Join-Path $privateDirectory "jwt-private.pem"
$publicKeyPath = Join-Path $privateDirectory "jwt-public.pem"
$privateFiles = @($identityEnvironmentPath, $privateKeyPath, $publicKeyPath)
$existingFiles = @($privateFiles | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })

$openssl = (Get-Command openssl.exe -ErrorAction Stop).Source

if ($existingFiles.Count -gt 0) {
    if ($existingFiles.Count -ne $privateFiles.Count) {
        throw "Local Identity private files are incomplete. They were not overwritten; inspect deploy/k3s/overlays/local/private."
    }
    $identityValues = Read-InfraEnvironment $identityEnvironmentPath
    Assert-IdentityEnvironment $identityValues
    $privateCheck = Invoke-InfraProcess -File $openssl -Arguments @(
        "pkey", "-in", $privateKeyPath, "-noout", "-check"
    ) -TimeoutSeconds 30 -Directory $repositoryRoot
    Assert-InfraResult $privateCheck "JWT private key validation" @{} 
    $publicCheck = Invoke-InfraProcess -File $openssl -Arguments @(
        "pkey", "-pubin", "-in", $publicKeyPath, "-noout"
    ) -TimeoutSeconds 30 -Directory $repositoryRoot
    Assert-InfraResult $publicCheck "JWT public key validation" @{}
    Write-Host "[PASS] Existing local Identity secrets and JWT keys are complete; nothing was overwritten." -ForegroundColor Green
    exit 0
}

$infra = Read-InfraEnvironment $infraPath
foreach ($key in @(
    "IDENTITY_DB_USERNAME", "IDENTITY_DB_PASSWORD",
    "MINIPAY_REDIS_PASSWORD", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD"
)) {
    if (-not $infra.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($infra[$key])) {
        throw "Compose private configuration is missing $key (value hidden)."
    }
}

[void](New-Item -ItemType Directory -Path $privateDirectory -Force)

$privateResult = Invoke-InfraProcess -File $openssl -Arguments @(
    "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072", "-out", $privateKeyPath
) -TimeoutSeconds 60 -Directory $repositoryRoot
Assert-InfraResult $privateResult "JWT private key generation" @{}

$publicResult = Invoke-InfraProcess -File $openssl -Arguments @(
    "pkey", "-in", $privateKeyPath, "-pubout", "-out", $publicKeyPath
) -TimeoutSeconds 30 -Directory $repositoryRoot
Assert-InfraResult $publicResult "JWT public key generation" @{}

$identityLines = @(
    "MYSQL_USERNAME=$($infra.IDENTITY_DB_USERNAME)",
    "MYSQL_PASSWORD=$($infra.IDENTITY_DB_PASSWORD)",
    "REDIS_PASSWORD=$($infra.MINIPAY_REDIS_PASSWORD)",
    "RABBITMQ_USERNAME=$($infra.RABBITMQ_USERNAME)",
    "RABBITMQ_PASSWORD=$($infra.RABBITMQ_PASSWORD)",
    "PHONE_HASH_PEPPER=$(New-RandomHex)",
    "PHONE_DISCLOSURE_ENCRYPTION_KEY=$(New-RandomHex)",
    "PHONE_DISCLOSURE_KEY_ID=local-k3s-v1",
    "EMAIL_HASH_PEPPER=$(New-RandomHex)",
    "TOKEN_DIGEST_PEPPER=$(New-RandomHex)",
    "PAYMENT_AUTHORIZATION_TOKEN_KEY=$(New-RandomHex)",
    "REAL_NAME_HMAC_KEY=$(New-RandomHex)",
    "AUTH_AUDIT_PEPPER=$(New-RandomHex)",
    "CAPTCHA_PEPPER=$(New-RandomHex)",
    "MANAGEMENT_OAUTH_CLIENT_SECRET=$(New-RandomHex)",
    "ADMIN_OAUTH_CLIENT_SECRET=$(New-RandomHex)",
    "PAYMENT_TO_IDENTITY_CLIENT_SECRET=$(New-RandomHex)",
    "PAYMENT_TO_WALLET_CLIENT_SECRET=$(New-RandomHex)",
    "WALLET_TO_IDENTITY_CLIENT_SECRET=$(New-RandomHex)",
    "AGENT_TO_PAYMENT_CLIENT_SECRET=$(New-RandomHex)",
    "AGENT_TO_IDENTITY_CLIENT_SECRET=$(New-RandomHex)",
    "AGENT_DELEGATION_CLIENT_SECRET=$(New-RandomHex)",
    "IDENTITY_TO_PAYMENT_CLIENT_SECRET=$(New-RandomHex)",
    "COMMERCE_TO_IDENTITY_CLIENT_SECRET=$(New-RandomHex)"
)

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($identityEnvironmentPath, $identityLines, $utf8)

$createdValues = Read-InfraEnvironment $identityEnvironmentPath
Assert-IdentityEnvironment $createdValues
Write-Host "[PASS] Created local Identity secrets and persistent JWT keys (values hidden)." -ForegroundColor Green
Write-Host "[INFO] Files are gitignored and were not applied to Kubernetes."
