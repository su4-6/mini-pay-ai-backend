[CmdletBinding()]
param(
    [ValidateSet('Validate','Start','Check','Probe')][string]$Action = 'Validate',
    [string]$ExpectedContext = 'docker-desktop',
    [string]$InfraHost = 'host.docker.internal'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'infra-common.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envPath = Join-Path $repo 'deploy\compose-infra\.env.local'
$values = @{}
$compose = @('compose','--project-name','minipay-infra','--env-file',$envPath,
    '-f',(Join-Path $repo 'deploy\compose-infra\compose.yaml'),
    '-f',(Join-Path $repo 'deploy\compose-infra\compose.bootstrap-local.yaml'))
$services = @('minipay-mysql','minipay-redis','rabbitmq','seata-server','yshop-mysql','yshop-redis')
$mysqlBody = Get-Content (Join-Path $PSScriptRoot 'checks\mysql.sh') -Raw -Encoding UTF8
$passed = New-Object 'System.Collections.Generic.List[string]'

function Pass([string]$Step) {
    $passed.Add($Step)
    Write-Host "[PASS] $Step"
}
function Save-Report([string]$Status) {
    $report = @{
        timestamp=[DateTime]::UtcNow.ToString('o');action=$Action;status=$Status;passed=@($passed.ToArray())
        pending=@('Coturn media/UDP','Business AMQP events and TCC','Backup/restore and persistence test',
                  'Business workloads, images, OAuth, all pages, Android, server migration')
    }
    $directory = Join-Path $repo 'deploy\compose-infra\generated'
    [void][System.IO.Directory]::CreateDirectory($directory)
    $path = Join-Path $directory ("infra-" + $Action.ToLowerInvariant() + '.json')
    [System.IO.File]::WriteAllText($path, ($report | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "[INFO] Stage evidence: $path"
}
function Run-Docker([string[]]$Arguments, [string]$InputText = '', [int]$Timeout = 60) {
    Invoke-InfraProcess -File docker.exe -Arguments $Arguments -InputText $InputText -TimeoutSeconds $Timeout -Directory $repo
}
function Run-Kube([string[]]$Arguments, [string]$InputText = '', [int]$Timeout = 60) {
    $requestTimeout = [Math]::Max(10, $Timeout - 5)
    Invoke-InfraProcess -File kubectl.exe -Arguments (@('--context',$ExpectedContext,"--request-timeout=${requestTimeout}s") + $Arguments) -InputText $InputText -TimeoutSeconds $Timeout -Directory $repo
}
function Check-Static {
    $ignored = Invoke-InfraProcess git.exe @('-C',$repo,'check-ignore','--quiet','--','deploy/compose-infra/.env.local')
    if ($ignored.ExitCode -ne 0) { throw '.env.local must be ignored by Git.' }
    $tracked = Invoke-InfraProcess git.exe @('-C',$repo,'ls-files','--','deploy/compose-infra/.env.local')
    if ($tracked.ExitCode -ne 0 -or $tracked.Output.Trim()) { throw '.env.local must not be tracked by Git.' }
    Test-InfraVariables $values
    $result = Run-Docker ($compose + @('--profile','voice','config','--format','json'))
    # Compose may print private interpolated configuration. Keep its result in memory.
    if ($result.ExitCode -ne 0) { throw 'Compose configuration could not be resolved. Check required variables and YAML; private output was suppressed.' }
    $script:configuration = $result.Output | ConvertFrom-Json
    $actualNames = @($configuration.services.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @(($services + 'coturn') | Sort-Object)
    if (@(Compare-Object $actualNames $expectedNames).Count -gt 0) {
        throw 'Compose must contain only the seven agreed infrastructure services.'
    }
    foreach ($property in $configuration.services.PSObject.Properties) {
        $service = $property.Value
        if ($service.image -match ':latest$' -or $service.image -notmatch ':[^/]+$|@sha256:[a-f0-9]{64}$') {
            throw "Image must have an explicit version: $($property.Name)"
        }
        if ($service.PSObject.Properties['volumes']) {
            foreach ($mount in $service.volumes) {
                if ($mount.type -ne 'bind') { continue }
                if (-not (Test-Path -LiteralPath $mount.source -PathType Leaf)) { throw "Missing mount file: $($mount.source)" }
                if (-not $mount.read_only) { throw "Config/SQL mounts must be read-only: $($mount.target)" }
                if ($mount.source -like '*.sh') {
                    $content = [System.IO.File]::ReadAllText($mount.source)
                    if ($content.Contains("`r")) { throw "Shell script requires LF line endings: $($mount.source)" }
                }
            }
        }
    }
    $sql = @($configuration.services.'yshop-mysql'.volumes | Where-Object { $_.target -like '/docker-entrypoint-initdb.d/*' })
    if ($sql.Count -ne 6) { throw 'Exactly six YShop local bootstrap SQL mounts are required.' }
    Pass 'Static configuration: seven infrastructure services, private variables, source paths, six SQL mounts'
}
function Require-Docker {
    $context = Run-Docker @('context','inspect','--format','{{.Endpoints.docker.Host}}')
    Assert-InfraResult $context 'Docker context inspection' $values
    if ($context.Output.Trim() -notlike 'npipe:*') { throw 'This local command only supports a Windows Docker Desktop named-pipe endpoint.' }
    $result = Run-Docker @('info','--format','{{.OSType}}')
    if ($result.ExitCode -ne 0) { throw 'Docker Desktop Linux engine is unavailable. Start Docker Desktop, wait for Engine running, then rerun the same command.' }
    if ($result.Output.Trim() -ne 'linux') { throw 'Docker Desktop must use Linux containers.' }
    Pass 'Docker Desktop Linux engine'
}
function Container-Id([string]$Service) {
    $result = Run-Docker ($compose + @('ps','-q',$Service))
    Assert-InfraResult $result "Find $Service" $values
    $id = $result.Output.Trim()
    if ($id -notmatch '^[a-f0-9]{12,64}$') { throw "$Service is not running. Use -Action Start." }
    return $id
}
function Verify-Mysql([string]$Kind, [string]$Container) {
    $variables = Get-InfraMysqlVariables $values $Kind '127.0.0.1' 3306
    $inputText = New-InfraShellInput $variables $mysqlBody
    $result = Run-Docker @('exec','-i',$Container,'bash','-s') $inputText 150
    Assert-InfraResult $result "$Kind SQL authentication/schema verification" $values -ShowOutput
    Pass "$Kind database authentication and required schema"
}
function Check-Containers {
    foreach ($service in $services) {
        $id = Container-Id $service
        $result = Run-Docker @('inspect','--format','{{.State.Health.Status}}',$id)
        Assert-InfraResult $result "$service health status" $values
        if ($result.Output.Trim() -ne 'healthy') { throw "$service is not healthy. No data has been removed." }
        Pass "$service container health"
    }
    Verify-Mysql 'minipay' (Container-Id 'minipay-mysql')
    Verify-Mysql 'yshop' (Container-Id 'yshop-mysql')
    foreach ($entry in @(@('minipay-redis','MINIPAY_REDIS_PASSWORD'), @('yshop-redis','YSHOP_REDIS_PASSWORD'))) {
        $body = 'test "$(redis-cli --no-auth-warning ping)" = PONG'
        $inputText = New-InfraShellInput @{ REDISCLI_AUTH=$values[$entry[1]] } $body
        $result = Run-Docker @('exec','-i',(Container-Id $entry[0]),'sh','-s') $inputText
        Assert-InfraResult $result "$($entry[0]) authentication using .env.local" $values
        Pass "$($entry[0]) authentication"
    }
}
function Start-Containers {
    # Never regenerate secrets, import SQL into an existing volume, or use down -v.
    foreach ($group in @(@('minipay-mysql'), @('minipay-redis','rabbitmq','yshop-mysql','yshop-redis'), @('seata-server'))) {
        Write-Host "[RUN] Starting/waiting for: $($group -join ', ')"
        $result = Run-Docker ($compose + @('up','-d','--wait','--wait-timeout','300') + $group) '' 420
        Assert-InfraResult $result 'Compose start/wait' $values
        if ($group -contains 'minipay-mysql') { Verify-Mysql 'minipay' (Container-Id 'minipay-mysql') }
    }
}
function Check-Pod {
    # This mutation runs only when the learner explicitly invokes Start or Probe.
    if ($ExpectedContext -ne 'docker-desktop') { throw 'This script is local-only. Server deployment uses a separate reviewed workflow.' }
    if ($InfraHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$') { throw 'Invalid infrastructure hostname.' }
    $result = Run-Kube @('get','nodes','-o','name')
    Assert-InfraResult $result 'Local Kubernetes availability' $values
    $result = Run-Kube @('apply','-f',(Join-Path $repo 'deploy\k3s\base\namespace.yaml'))
    Assert-InfraResult $result 'Ensure minipay namespace' $values
    $podName = 'minipay-infra-probe-' + [guid]::NewGuid().ToString('N').Substring(0,12)
    $uid = $null
    $containers = @()
    foreach ($entry in @(@('mysql',[string]$configuration.services.'minipay-mysql'.image),
                          @('redis',[string]$configuration.services.'minipay-redis'.image),
                          @('network','python:3.12-alpine'))) {
        $containers += @{
            name=$entry[0]; image=$entry[1]; imagePullPolicy='IfNotPresent'; command=@('sleep','900')
            resources=@{ requests=@{cpu='10m';memory='32Mi'}; limits=@{cpu='200m';memory='128Mi'} }
            securityContext=@{allowPrivilegeEscalation=$false;readOnlyRootFilesystem=$true;runAsNonRoot=$true;runAsUser=1000;capabilities=@{drop=@('ALL')}}
        }
    }
    $manifest = @{
        apiVersion='v1';kind='Pod';metadata=@{name=$podName;namespace='minipay';labels=@{'app.kubernetes.io/name'='minipay-infra-probe'}}
        spec=@{restartPolicy='Never';activeDeadlineSeconds=900;automountServiceAccountToken=$false;
            securityContext=@{seccompProfile=@{type='RuntimeDefault'}};containers=$containers}
    } | ConvertTo-Json -Depth 15
    try {
        Write-Host "[RUN] Temporary diagnostic Pod: minipay/$podName (contains no Secret values)"
        $result = Run-Kube @('create','-f','-','-o','json') $manifest
        Assert-InfraResult $result 'Create diagnostic Pod' $values
        $uid = ($result.Output | ConvertFrom-Json).metadata.uid
        $result = Run-Kube @('-n','minipay','wait','--for=condition=Ready',"pod/$podName",'--timeout=180s') '' 210
        Assert-InfraResult $result 'Diagnostic Pod readiness; image pulling may require network access' $values
        foreach ($kind in 'minipay','yshop') {
            $portKey = if ($kind -eq 'minipay') { 'MINIPAY_MYSQL_HOST_PORT' } else { 'YSHOP_MYSQL_HOST_PORT' }
            $vars = Get-InfraMysqlVariables $values $kind $InfraHost ([int]$values[$portKey])
            $result = Run-Kube @('-n','minipay','exec','-i',$podName,'-c','mysql','--','bash','-s') (New-InfraShellInput $vars $mysqlBody) 150
            Assert-InfraResult $result "Pod -> $kind MySQL authentication" $values -ShowOutput
            Pass "Pod -> $kind MySQL authentication"
        }
        foreach ($prefix in 'MINIPAY','YSHOP') {
            $vars = @{REDISCLI_AUTH=$values["${prefix}_REDIS_PASSWORD"];VERIFY_HOST=$InfraHost;VERIFY_PORT=$values["${prefix}_REDIS_HOST_PORT"]}
            $body = 'test "$(redis-cli --no-auth-warning -h "$VERIFY_HOST" -p "$VERIFY_PORT" ping)" = PONG'
            $result = Run-Kube @('-n','minipay','exec','-i',$podName,'-c','redis','--','sh','-s') (New-InfraShellInput $vars $body)
            Assert-InfraResult $result "Pod -> $prefix Redis authentication" $values
            Pass "Pod -> $prefix Redis authentication"
        }
        $networkConfig = @{
            host=$InfraHost; ports=@{RabbitMQ=$values.RABBITMQ_HOST_PORT;Seata=$values.SEATA_SERVICE_HOST_PORT}
            rabbit_user=$values.RABBITMQ_USERNAME;rabbit_password=$values.RABBITMQ_PASSWORD
            rabbit_management_port=$values.RABBITMQ_MANAGEMENT_HOST_PORT
        } | ConvertTo-Json -Compress
        $python = Get-Content (Join-Path $PSScriptRoot 'checks\network.py') -Raw -Encoding UTF8
        $body = 'printf %s ' + (ConvertTo-ShellLiteral $networkConfig) + ' | python3 -c ' + (ConvertTo-ShellLiteral $python)
        $result = Run-Kube @('-n','minipay','exec','-i',$podName,'-c','network','--','sh','-s') (New-InfraShellInput @{} $body)
        Assert-InfraResult $result 'Pod -> RabbitMQ/Seata network checks' $values -ShowOutput
        Pass 'Pod -> RabbitMQ/Seata TCP and RabbitMQ API credentials'
    }
    finally {
        if ($uid) {
            $current = Run-Kube @('-n','minipay','get','pod',$podName,'-o','jsonpath={.metadata.uid}')
            if ($current.ExitCode -eq 0 -and $current.Output.Trim() -eq $uid) {
                $removed = Run-Kube @('-n','minipay','delete','pod',$podName,'--wait=false')
                if ($removed.ExitCode -eq 0) { Write-Host '[INFO] Removed only the diagnostic Pod created by this run; namespace and data retained.' }
                else { Write-Host "[PENDING] Cleanup of diagnostic Pod minipay/$podName" }
            } else { Write-Host "[INFO] Diagnostic Pod minipay/$podName could not be matched by UID; no deletion attempted." }
        }
    }
}

try {
    $values = Read-InfraEnvironment $envPath
    Write-Host "[STAGE] Local infrastructure: $Action"
    Check-Static
    if ($Action -ne 'Validate') {
        Require-Docker
        if ($Action -eq 'Start') { Start-Containers }
        Check-Containers
        if ($Action -in @('Start','Probe')) { Check-Pod }
    }
    Save-Report 'passed'
    Write-Host '[PASS] Requested stage completed.'
    Write-Host '[PENDING] Full deployment is not accepted until business, pages, Android and server checks pass.'
}
catch {
    Write-Host ('[FAIL] ' + (Protect-InfraOutput $_.Exception.Message $values))
    try { Save-Report 'failed' } catch { Write-Host '[INFO] Could not write the stage report; the stage has failed.' }
    Write-Host '[STOP] Keep existing passwords and volumes. Fix the reported item and rerun this stage.'
    exit 1
}
