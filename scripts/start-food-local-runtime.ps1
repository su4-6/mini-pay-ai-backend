param(
    [switch]$Stop,
    [switch]$YshopOnly,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$YshopRoot = $env:YSHOP_ROOT
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot '.runtime'
$servicePorts = if ($YshopOnly) { @(48081) } else { @(8081, 8082, 8083, 8085, 8086, 48081) }

function Resolve-Java21 {
    $candidates = @()
    if ($JavaHome) {
        $candidates += Join-Path $JavaHome 'bin\java.exe'
    }
    $candidates += Get-ChildItem -Path (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Match '21' |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
    $candidates += 'C:\Program Files\Java\jdk-21\bin\java.exe'
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) {
        $candidates += $command.Source
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $candidate)) {
            continue
        }
        $jdkRoot = Split-Path -Parent (Split-Path -Parent $candidate)
        $releaseFile = Join-Path $jdkRoot 'release'
        if ((Test-Path -LiteralPath $releaseFile) -and
            ((Get-Content -LiteralPath $releaseFile -Raw) -match 'JAVA_VERSION="21(?:\.|\")')) {
            return $candidate
        }
    }
    throw 'Java 21 not found. Set JAVA_HOME or pass -JavaHome with a JDK 21 directory.'
}

function Resolve-YshopRoot {
    $candidates = @(
        $YshopRoot,
        'D:\WorkSpace\yshop-drink\yshop-drink-boot3',
        'D:\yshop-drink\yshop-drink-boot3'
    ) | Where-Object { $_ }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        $resolved = Resolve-Path -LiteralPath $candidate -ErrorAction SilentlyContinue
        if ($resolved -and
            (Test-Path -LiteralPath (Join-Path $resolved.Path 'pom.xml')) -and
            (Test-Path -LiteralPath (Join-Path $resolved.Path 'yshop-server'))) {
            return $resolved.Path
        }
    }
    throw 'yshop-drink repository not found. Set YSHOP_ROOT or pass -YshopRoot.'
}

$java = Resolve-Java21
$YshopRoot = Resolve-YshopRoot

function Stop-LocalFoodServices {
    foreach ($port in $servicePorts) {
        $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        foreach ($listener in $listeners) {
            $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
            if ($null -ne $process -and $process.ProcessName -eq 'java') {
                Stop-Process -Id $process.Id -Force
            }
        }
    }
}

if ($Stop) {
    Stop-LocalFoodServices
    return
}

Stop-LocalFoodServices
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$envFile = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^[A-Za-z_][A-Za-z0-9_]*=') {
            $pair = $_ -split '=', 2
            Set-Item -Path "Env:$($pair[0])" -Value $pair[1]
        }
    }
}

$env:SEATA_ENABLED = 'true'
$env:SEATA_SERVER_ADDR = 'localhost:8091'

function Start-MiniPayService(
    [string]$Name,
    [string]$Schema,
    [string]$Jar,
    [string[]]$Arguments = @()
) {
    $env:MYSQL_URL = "jdbc:mysql://localhost:3306/$Schema`?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    $process = Start-Process -FilePath $java `
        -ArgumentList (@('-jar', $Jar) + $Arguments) `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir "$Name.out.log") `
        -RedirectStandardError (Join-Path $logDir "$Name.err.log") `
        -PassThru
    Write-Output "$Name started: pid=$($process.Id)"
}

if (-not $YshopOnly) {
    Start-MiniPayService 'identity' 'minipay_identity' `
        (Join-Path $repoRoot 'services\identity-service\target\identity-service-0.1.0-SNAPSHOT.jar') `
        @('--spring.profiles.active=demo-auth')
    Start-MiniPayService 'wallet' 'minipay_wallet' `
        (Join-Path $repoRoot 'services\wallet-service\target\wallet-service-0.1.0-SNAPSHOT.jar')

    $env:WALLET_BASE_URL = 'http://localhost:8083'
    Start-MiniPayService 'payment' 'minipay_payment' `
        (Join-Path $repoRoot 'services\payment-service\target\payment-service-0.1.0-SNAPSHOT.jar') `
        @('--spring.profiles.active=demo-data')

    $env:IDENTITY_INTERNAL_URL = 'http://localhost:8081'
    $env:YSHOP_INTERNAL_URL = 'http://localhost:48081'
    Start-MiniPayService 'commerce' 'minipay_commerce' `
        (Join-Path $repoRoot 'services\commerce-service\target\commerce-service-0.1.0-SNAPSHOT.jar')

    $env:PAYMENT_INTERNAL_URL = 'http://localhost:8082'
    $env:COMMERCE_INTERNAL_URL = 'http://localhost:8085'
    Start-MiniPayService 'agent' 'minipay_agent' `
        (Join-Path $repoRoot 'services\agent-service\target\agent-service-0.1.0-SNAPSHOT.jar')
}

Remove-Item Env:MYSQL_URL -ErrorAction SilentlyContinue
$env:MINIPAY_COMMERCE_INTERNAL_URL = 'http://localhost:8085'
$env:YSHOP_MINIPAY_H5_ORIGIN = 'http://127.0.0.1:4173'
$yshopJar = Join-Path $YshopRoot 'yshop-server\target\yshop-server.jar'
if (-not (Test-Path -LiteralPath $yshopJar)) {
    throw "yshop server JAR not found: $yshopJar. Build it with Maven before starting."
}
$yshop = Start-Process -FilePath $java `
    -ArgumentList @('-jar', $yshopJar, '--spring.profiles.active=local', '--server.port=48081') `
    -WorkingDirectory $YshopRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir 'yshop.out.log') `
    -RedirectStandardError (Join-Path $logDir 'yshop.err.log') `
    -PassThru
Write-Output "yshop started: pid=$($yshop.Id)"
Write-Output "logs: $logDir"
