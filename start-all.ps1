# MiniPay one-click startup — all infrastructure + all services
# Usage: ./start-all.ps1 [-SkipBuild] [-SkipDbInit] [-Down] [-Logs]

param(
  [switch] $SkipBuild,
  [switch] $SkipDbInit,
  [switch] $Down,
  [switch] $Logs
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot

# ── .env ─────────────────────────────────────────────────────────────────
if (-not (Test-Path "$ProjectRoot\.env")) {
  Write-Warning ".env not found — copying .env.example. Edit it before first run."
  Copy-Item "$ProjectRoot\.env.example" "$ProjectRoot\.env"
}
Get-Content "$ProjectRoot\.env" | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]+?)\s*=\s*(.*?)\s*$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], "Process")
  }
}

# ── helpers ──────────────────────────────────────────────────────────────
function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

function Die($msg) {
  Write-Host "FATAL: $msg" -ForegroundColor Red
  exit 1
}

function Test-PaymentIdentityAuthorization {
  $clientId = if (Test-Path env:PAYMENT_IDENTITY_CLIENT_ID) { $env:PAYMENT_IDENTITY_CLIENT_ID } else { "minipay-payment-to-identity" }
  $secret = if (Test-Path env:PAYMENT_TO_IDENTITY_CLIENT_SECRET) { $env:PAYMENT_TO_IDENTITY_CLIENT_SECRET } else { $null }
  if ([string]::IsNullOrWhiteSpace($secret)) {
    Write-Warning "Payment -> Identity authorization check skipped: PAYMENT_TO_IDENTITY_CLIENT_SECRET is not configured."
    return
  }
  try {
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$clientId`:$secret"))
    $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Method Post `
      -Uri "http://127.0.0.1:8081/oauth2/token" `
      -Headers @{ Authorization = "Basic $basic" } `
      -ContentType "application/x-www-form-urlencoded" `
      -Body "grant_type=client_credentials&scope=identity.payment-authorization.verify"
    Write-Host "Payment -> Identity authorization is ready." -ForegroundColor Green
  } catch {
    $status = $_.Exception.Response.StatusCode.value__
    Write-Warning "Payment -> Identity authorization check failed (HTTP $status). Check the local internal-client configuration."
  }
}

# ── down mode ────────────────────────────────────────────────────────────
if ($Down) {
  Step "Tearing down all services"
  docker compose --profile apps down --volumes --remove-orphans
  Write-Host "Done." -ForegroundColor Green
  exit 0
}

# ── prerequisites ────────────────────────────────────────────────────────
Step "Checking prerequisites"

$null = docker info 2>&1
if ($LASTEXITCODE -ne 0) { Die "Docker is not running." }

$mysqlPort = if (Test-Path env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3306" }
$mysqlUser = if (Test-Path env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }
$mysqlPass = if (Test-Path env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { "" }
$mysqlPass = $mysqlPass.Trim()

# Test MySQL connectivity (non-fatal — mysql CLI may not be on PATH)
try {
  $mysqlArgs = @("-u", $mysqlUser, "-P", $mysqlPort, "-e", "SELECT 1")
  if ($mysqlPass) { $mysqlArgs = @("-u", $mysqlUser, "-p$mysqlPass", "-P", $mysqlPort, "-e", "SELECT 1") }
  $null = & mysql $mysqlArgs 2>&1
  if ($LASTEXITCODE -ne 0) {
    Write-Warning "MySQL not reachable on port $mysqlPort as $mysqlUser — continuing anyway"
  } else {
    Write-Host "MySQL reachable." -ForegroundColor Green
  }
} catch {
  Write-Warning "mysql CLI not found — skip connectivity check"
}

# DB init (non-fatal — mysql CLI may not be on PATH)
if (-not $SkipDbInit) {
  Step "Initializing MySQL databases"
  $sqlFile = "$ProjectRoot\docker\init-local-mysql.sql"
  if (Test-Path $sqlFile) {
    try {
      $initArgs = @("-u", $mysqlUser, "-P", $mysqlPort)
      if ($mysqlPass) { $initArgs = @("-u", $mysqlUser, "-p$mysqlPass", "-P", $mysqlPort) }
      Get-Content $sqlFile | & mysql $initArgs 2>&1
      if ($LASTEXITCODE -ne 0) {
        Write-Warning "DB init had errors — databases may already exist (ok)"
      } else {
        Write-Host "Databases ready." -ForegroundColor Green
      }
    } catch {
      Write-Warning "mysql CLI not found — skip DB init (run docker/init-local-mysql.sql manually)"
    }
  } else {
    Write-Warning "$sqlFile not found — skipping"
  }
}

# ── build ────────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
  Step "Building all modules (./mvnw package -DskipTests)"
  Push-Location $ProjectRoot
  try {
    $mvnw = "$ProjectRoot\mvnw.cmd"
    if (Test-Path $mvnw) {
      & cmd /c "`"$mvnw`" package -DskipTests -B -ntp"
    } else {
      & mvn package -DskipTests -B -ntp
    }
    if ($LASTEXITCODE -ne 0) { Die "Maven build failed." }
    Write-Host "Build complete." -ForegroundColor Green
  } finally {
    Pop-Location
  }
} else {
  Write-Host "Skipping build (--skip-build)." -ForegroundColor Yellow
}

# ── infra ────────────────────────────────────────────────────────────────
Step "Starting infrastructure (Redis, RabbitMQ, Seata)"
Push-Location $ProjectRoot
try {
  docker compose up -d redis rabbitmq seata-server
  if ($LASTEXITCODE -ne 0) { Die "Infrastructure startup failed." }
  Write-Host "Infrastructure starting..." -ForegroundColor Green
} finally {
  Pop-Location
}

# ── apps ─────────────────────────────────────────────────────────────────
Step "Starting all app services"
Push-Location $ProjectRoot
try {
  docker compose --profile apps up -d --build
  if ($LASTEXITCODE -ne 0) { Die "App startup failed." }
} finally {
  Pop-Location
}

# ── status ───────────────────────────────────────────────────────────────
Step "Service status"
Start-Sleep -Seconds 3
docker compose ps

Write-Host "`n=== All services launched ===" -ForegroundColor Green
Write-Host @"

  identity-service : http://localhost:8081
  payment-service  : http://localhost:8082
  wallet-service   : http://localhost:8083
  agent-service    : http://localhost:8086
  consumer-bff     : http://localhost:8087
  management-bff   : http://localhost:8088
  RabbitMQ mgmt    : http://localhost:15672
  Seata console    : http://localhost:7091

  Logs : docker compose logs -f
  Stop : docker compose --profile apps down
  Rebuild: ./start-all.ps1
"@

Test-PaymentIdentityAuthorization

# ── optional tail logs ──────────────────────────────────────────────────
if ($Logs) {
  Push-Location $ProjectRoot
  docker compose logs -f
  Pop-Location
}
