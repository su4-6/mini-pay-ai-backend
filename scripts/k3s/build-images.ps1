[CmdletBinding()]
param(
    [ValidateSet(
        "identity-service",
        "payment-service",
        "wallet-service",
        "agent-service",
        "commerce-service",
        "consumer-bff",
        "management-bff",
        "admin-bff"
    )]
    [string[]] $Service = @(
        "identity-service",
        "payment-service",
        "wallet-service",
        "agent-service",
        "commerce-service",
        "consumer-bff",
        "management-bff",
        "admin-bff"
    ),

    [ValidatePattern('^[a-z0-9][a-z0-9._-]*$')]
    [string] $Registry = "suqihang",

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string] $Tag = "0.1.0-k3s.1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string] $Message) {
    Write-Host "[STAGE] $Message" -ForegroundColor Cyan
}

function Write-Pass([string] $Message) {
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Find-Java21Home {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME
    }
    $candidates += Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles "Microsoft") -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -ExpandProperty FullName
    $candidates += Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles "Eclipse Adoptium") -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -ExpandProperty FullName

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $java = Join-Path $candidate "bin/java.exe"
        if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
            continue
        }
        $versionText = (Get-Item -LiteralPath $java).VersionInfo.ProductVersion
        if ($versionText -match '^21\.') {
            return $candidate
        }
    }
    throw "Java 21 was not found. Install a JDK 21 before building MiniPay release images."
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$previousLocation = Get-Location
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path

try {
    Set-Location -LiteralPath $repositoryRoot

    $java21Home = Find-Java21Home
    $env:JAVA_HOME = $java21Home
    $env:Path = "$(Join-Path $java21Home 'bin');$previousPath"
    Write-Pass "Java 21: $java21Home"

    Write-Stage "Clean and verify the complete source tree once"
    & mvn.cmd -B -ntp clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed (exit $LASTEXITCODE). No release image was created."
    }
    Write-Pass "Complete Maven reactor verification from a clean build"

    Write-Stage "Create runtime images from verified jars"
    foreach ($serviceName in $Service) {
        $module = "services/$serviceName"
        $targetDirectory = Join-Path $repositoryRoot "$module/target"
        $jarFiles = @(Get-ChildItem -LiteralPath $targetDirectory -Filter "*.jar" -File -ErrorAction Stop)
        if ($jarFiles.Count -ne 1) {
            throw "Expected exactly one verified runnable jar for $serviceName, found $($jarFiles.Count)."
        }

        $image = "${Registry}/${serviceName}:${Tag}"
        Write-Host "[RUN] $image"
        $dockerArguments = @(
            "build",
            "--file", "docker/k3s-service.Dockerfile",
            "--build-arg", "MODULE=$module",
            "--tag", $image,
            "."
        )
        & docker @dockerArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Docker image build failed for $image (exit $LASTEXITCODE)."
        }
        Write-Pass $image
    }

    Write-Pass "Requested Java images completed; nothing was pushed to Docker Hub."
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
    Set-Location -LiteralPath $previousLocation
}
