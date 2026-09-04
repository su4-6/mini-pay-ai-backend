param(
    [ValidateSet('infra', 'payment', 'apps')]
    [string]$Target = 'infra',
    [ValidateRange(1, 65535)]
    [int]$MysqlPort = 3306,
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$MysqlDatabase = 'minipay_payment',
    [switch]$UseLocalInfraFallback
)

$merchantJavaHome = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
if (-not (Test-Path "$merchantJavaHome\bin\java.exe")) {
    throw "JDK 21 was not found at $merchantJavaHome. Install JDK 21 or update this script."
}

$env:JAVA_HOME = $merchantJavaHome
$env:Path = "$merchantJavaHome\bin;$env:Path"

if ($Target -eq 'infra') {
    if ($UseLocalInfraFallback) {
        # Use this only when MySQL 8.4/Seata images cannot be pulled.  It deliberately
        # does not start Seata, so the payment target also disables Seata in this mode.
        $env:MYSQL_CORE_PORT = $MysqlPort
        docker compose -f compose.yaml -f compose.infra-local.yaml up -d mysql-core redis rabbitmq
        Write-Host "Fallback infrastructure is starting on MySQL port $MysqlPort. Seata is not started."
        exit $LASTEXITCODE
    }

    $env:MYSQL_CORE_PORT = $MysqlPort
    docker compose -f compose.yaml up -d mysql-core redis rabbitmq seata-server
    Write-Host 'Infrastructure is starting. Check: docker compose ps'
    exit $LASTEXITCODE
}

if ($Target -eq 'apps') {
    docker compose --profile apps up --build
    exit $LASTEXITCODE
}

if ($MysqlPort -ne 3306 -or $MysqlDatabase -ne 'minipay_payment') {
    $env:MYSQL_URL = "jdbc:mysql://localhost:$MysqlPort/$MysqlDatabase?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
}

if (-not $env:MERCHANT_APP_SECRET_KEY) {
    # Development-only 32-byte key. Production must inject its own managed key.
    $env:MERCHANT_APP_SECRET_KEY = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8='
    Write-Warning 'Using the bundled development MERCHANT_APP_SECRET_KEY. Set a managed key outside local development.'
}

if ($UseLocalInfraFallback) {
    $env:SEATA_ENABLED = 'false'
}

# -f makes Spring Boot execute only payment-service.  Using -pl ... -am with the
# root reactor also tries to resolve spring-boot:run in parent modules.
mvn.cmd -B -ntp -f services/payment-service/pom.xml spring-boot:run
