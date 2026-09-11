[CmdletBinding()]
param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-RandomHex {
    param(
        [int]$ByteLength = 32
    )

    $bytes = New-Object byte[] $ByteLength
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()

    try {
        $random.GetBytes($bytes)
    }
    finally {
        $random.Dispose()
    }

    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$targetPath = Join-Path $repoRoot 'deploy\compose-infra\.env.local'
if ($Force) {
    throw 'Password replacement is disabled. Existing database volumes retain their passwords; use a separate planned credential rotation.'
}
if (Test-Path -LiteralPath $targetPath) {
    & git -C $repoRoot check-ignore --quiet -- 'deploy/compose-infra/.env.local'
    if ($LASTEXITCODE -ne 0) { throw 'The private environment file must be ignored by Git.' }
    Write-Host 'Existing .env.local preserved. No credentials were generated or changed.'
    Write-Host 'Next: powershell -File scripts/k3s/infra.ps1 -Action Validate'
    return
}
$resolvedYShopDir = (Resolve-Path (Join-Path $repoRoot 'integrations\yshop\server')).Path

$requiredSqlFiles = @(
    'sql\yixiang-drink-open.sql'
    'sql\migrations\minipay\V001__minipay_food_integration.sql'
    'sql\migrations\minipay\V002__minipay_authorization_profiles.sql'
    'sql\migrations\minipay\V003__minipay_address_location_drafts.sql'
)

foreach ($relativeFile in $requiredSqlFiles) {
    $sourceFile = Join-Path $resolvedYShopDir $relativeFile

    if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Required YShop SQL file was not found: $sourceFile"
    }
}

$relativeTarget = 'deploy/compose-infra/.env.local'
$targetPath = Join-Path $repoRoot 'deploy\compose-infra\.env.local'

& git -C $repoRoot check-ignore --quiet -- $relativeTarget

if ($LASTEXITCODE -ne 0) {
    throw "$relativeTarget is not ignored by Git. Stop to prevent committing secrets."
}

$secrets = [ordered]@{
    MINIPAY_MYSQL_ROOT_PASSWORD = New-RandomHex
    IDENTITY_DB_PASSWORD        = New-RandomHex
    PAYMENT_DB_PASSWORD         = New-RandomHex
    WALLET_DB_PASSWORD          = New-RandomHex
    COMMERCE_DB_PASSWORD        = New-RandomHex
    AGENT_DB_PASSWORD           = New-RandomHex
    SEATA_DB_PASSWORD           = New-RandomHex
    SEATA_CONSOLE_PASSWORD      = New-RandomHex
    SEATA_SECRET_KEY            = New-RandomHex
    MINIPAY_REDIS_PASSWORD      = New-RandomHex
    RABBITMQ_PASSWORD           = New-RandomHex
    YSHOP_MYSQL_PASSWORD        = New-RandomHex
    YSHOP_MYSQL_ROOT_PASSWORD   = New-RandomHex
    YSHOP_REDIS_PASSWORD        = New-RandomHex
    TURN_SHARED_SECRET          = New-RandomHex
}

$lines = @(
    '# Generated local-only infrastructure configuration.'
    '# Never commit or paste the values from this file into chat.'
    ''
    'INFRA_BIND_HOST=127.0.0.1'
    ''
    'MINIPAY_MYSQL_HOST_PORT=13306'
    'MINIPAY_REDIS_HOST_PORT=16379'
    'RABBITMQ_HOST_PORT=15673'
    'RABBITMQ_MANAGEMENT_HOST_PORT=15672'
    'SEATA_CONSOLE_HOST_PORT=7091'
    'SEATA_SERVICE_HOST_PORT=8091'
    'YSHOP_MYSQL_HOST_PORT=13307'
    'YSHOP_REDIS_HOST_PORT=16380'
    ''
    "MINIPAY_MYSQL_ROOT_PASSWORD=$($secrets.MINIPAY_MYSQL_ROOT_PASSWORD)"
    ''
    'IDENTITY_DB_USERNAME=minipay_identity_app'
    "IDENTITY_DB_PASSWORD=$($secrets.IDENTITY_DB_PASSWORD)"
    ''
    'PAYMENT_DB_USERNAME=minipay_payment_app'
    "PAYMENT_DB_PASSWORD=$($secrets.PAYMENT_DB_PASSWORD)"
    ''
    'WALLET_DB_USERNAME=minipay_wallet_app'
    "WALLET_DB_PASSWORD=$($secrets.WALLET_DB_PASSWORD)"
    ''
    'COMMERCE_DB_USERNAME=minipay_commerce_app'
    "COMMERCE_DB_PASSWORD=$($secrets.COMMERCE_DB_PASSWORD)"
    ''
    'AGENT_DB_USERNAME=minipay_agent_app'
    "AGENT_DB_PASSWORD=$($secrets.AGENT_DB_PASSWORD)"
    ''
    'SEATA_DB_USERNAME=seata'
    "SEATA_DB_PASSWORD=$($secrets.SEATA_DB_PASSWORD)"
    'SEATA_CONSOLE_USERNAME=seata'
    "SEATA_CONSOLE_PASSWORD=$($secrets.SEATA_CONSOLE_PASSWORD)"
    "SEATA_SECRET_KEY=$($secrets.SEATA_SECRET_KEY)"
    ''
    "MINIPAY_REDIS_PASSWORD=$($secrets.MINIPAY_REDIS_PASSWORD)"
    ''
    'RABBITMQ_USERNAME=minipay'
    "RABBITMQ_PASSWORD=$($secrets.RABBITMQ_PASSWORD)"
    ''
    'YSHOP_MYSQL_DATABASE=yixiang_drink'
    'YSHOP_MYSQL_USERNAME=yshop'
    "YSHOP_MYSQL_PASSWORD=$($secrets.YSHOP_MYSQL_PASSWORD)"
    "YSHOP_MYSQL_ROOT_PASSWORD=$($secrets.YSHOP_MYSQL_ROOT_PASSWORD)"
    ''
    "YSHOP_REDIS_PASSWORD=$($secrets.YSHOP_REDIS_PASSWORD)"
    ''
    'TURN_BIND_HOST=127.0.0.1'
    'TURN_EXTERNAL_IP=127.0.0.1'
    'TURN_REALM=minipay.local'
    "TURN_SHARED_SECRET=$($secrets.TURN_SHARED_SECRET)"
)

$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
# CreateNew also protects an environment file created by another process during setup.
$stream = [System.IO.File]::Open($targetPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
try {
    $bytes = $utf8WithoutBom.GetBytes(($lines -join "`n") + "`n")
    $stream.Write($bytes, 0, $bytes.Length)
}
finally { $stream.Dispose() }

Write-Host "Created private environment file: $targetPath"
Write-Host "Generated $($secrets.Count) secret values. Their contents were not printed."
Write-Host 'The infrastructure containers have not been started.'
