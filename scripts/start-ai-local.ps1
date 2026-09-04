param(
    [switch]$AgentOnly,
    [switch]$DisableModel
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'
$java = 'C:\Users\NxynB\.jdks\ms-21.0.12\bin\java.exe'
$logDir = Join-Path $env:TEMP 'minipay-ai-runtime'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local environment file: $envFile"
}
if (-not (Test-Path -LiteralPath $java)) {
    throw "Java 21 runtime not found: $java"
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^[A-Za-z_][A-Za-z0-9_]*=') {
        $pair = $_ -split '=', 2
        Set-Item -Path "Env:$($pair[0])" -Value $pair[1]
    }
}

function Set-DefaultEnvironment([string]$name, [string]$value) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Require-EnvironmentValue([string]$name) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Required local environment value is missing: $name"
    }
}

Set-DefaultEnvironment 'RABBITMQ_HOST' 'localhost'
Set-DefaultEnvironment 'RABBITMQ_PORT' '5672'
Set-DefaultEnvironment 'RABBITMQ_USERNAME' 'minipay'
Set-DefaultEnvironment 'RABBITMQ_PASSWORD' 'minipay'
Set-DefaultEnvironment 'REDIS_HOST' 'localhost'
Set-DefaultEnvironment 'REDIS_PORT' '16379'
Set-DefaultEnvironment 'SEATA_ENABLED' 'true'
Set-DefaultEnvironment 'SEATA_SERVER_ADDR' 'localhost:8091'
Set-DefaultEnvironment 'IDENTITY_INTERNAL_URL' 'http://localhost:8081'
Set-DefaultEnvironment 'IDENTITY_TOKEN_URL' 'http://localhost:8081/oauth2/token'
Set-DefaultEnvironment 'WALLET_INTERNAL_URL' 'http://localhost:8083'
Set-DefaultEnvironment 'WALLET_BASE_URL' 'http://localhost:8083'
Set-DefaultEnvironment 'PAYMENT_INTERNAL_URL' 'http://localhost:8082'
Set-DefaultEnvironment 'COMMERCE_INTERNAL_URL' 'http://localhost:8085'
Set-DefaultEnvironment 'LOCAL_IDENTITY_PROFILES' 'demo-auth'
Set-DefaultEnvironment 'AGENT_DATABASE' 'minipay_agent'

if ($env:MODEL_ENABLED -eq 'true' -and -not $DisableModel) {
    Require-EnvironmentValue 'MODEL_BASE_URL'
    Require-EnvironmentValue 'MODEL_NAME'
    Require-EnvironmentValue 'MODEL_API_KEY'
}
if ($env:OBJECT_STORAGE_PROVIDER -eq 'aliyun') {
    Require-EnvironmentValue 'ALIYUN_OSS_ENDPOINT'
    Require-EnvironmentValue 'ALIYUN_OSS_REGION'
    Require-EnvironmentValue 'ALIYUN_OSS_BUCKET'
    Require-EnvironmentValue 'ALIYUN_OSS_ACCESS_KEY_ID'
    Require-EnvironmentValue 'ALIYUN_OSS_ACCESS_KEY_SECRET'
}
if ($env:LOCAL_IDENTITY_PROFILES -notlike '*console-sms*' -and $env:SMS_PROVIDER -eq 'dypns') {
    Require-EnvironmentValue 'ALIYUN_DYPNS_ACCESS_KEY_ID'
    Require-EnvironmentValue 'ALIYUN_DYPNS_ACCESS_KEY_SECRET'
    Require-EnvironmentValue 'ALIYUN_DYPNS_SIGN_NAME'
    Require-EnvironmentValue 'ALIYUN_DYPNS_TEMPLATE_CODE'
    Require-EnvironmentValue 'ALIYUN_DYPNS_TEMPLATE_PARAM'
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Stop-PortProcess([int]$port) {
    $connections = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        if ($null -ne $process -and $process.ProcessName -eq 'java') {
            Stop-Process -Id $process.Id -Force
        }
    }
}

function Start-MiniPayService(
    [string]$name,
    [int]$port,
    [string]$database,
    [string]$jarRelativePath,
    [string[]]$arguments = @()
) {
    Stop-PortProcess $port
    $env:SERVER_PORT = "$port"
    $env:MYSQL_URL = "jdbc:mysql://localhost:13306/$database" +
            '?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    $jar = Join-Path $repoRoot $jarRelativePath
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Service jar not found: $jar"
    }
    $processArguments = @('-jar', $jar) + $arguments
    $process = Start-Process `
        -FilePath $java `
        -ArgumentList $processArguments `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir "$name.out.log") `
        -RedirectStandardError (Join-Path $logDir "$name.err.log") `
        -PassThru
    Write-Output "$name started: pid=$($process.Id), port=$port"
}

if (-not $AgentOnly) {
    Start-MiniPayService 'identity' 8081 'minipay_identity' `
        'services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar' `
        @("--spring.profiles.active=$env:LOCAL_IDENTITY_PROFILES")

    Start-MiniPayService 'wallet' 8083 'minipay_wallet' `
        'services/wallet-service/target/wallet-service-0.1.0-SNAPSHOT.jar'

    Start-MiniPayService 'payment' 8082 'minipay_payment' `
        'services/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar' `
        @('--spring.profiles.active=demo-data')

    Start-MiniPayService 'commerce' 8085 'minipay_commerce' `
        'services/commerce-service/target/commerce-service-0.1.0-SNAPSHOT.jar'
}

if ($DisableModel) {
    $env:MODEL_ENABLED = 'false'
    $env:MODEL_CHAT_MODE = 'none'
} elseif ([string]::IsNullOrWhiteSpace($env:MODEL_API_KEY)) {
    throw 'MODEL_API_KEY is empty. Fill it in .env or use -DisableModel.'
} else {
    $env:MODEL_ENABLED = 'true'
    $env:MODEL_CHAT_MODE = 'openai'
}

Start-MiniPayService 'agent' 8086 $env:AGENT_DATABASE `
    'services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar'

Write-Output "Runtime logs: $logDir"
