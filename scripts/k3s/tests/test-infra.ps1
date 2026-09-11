# Regression tests. No Docker or Kubernetes writes, no real credential output.
param([string]$BashPath = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\infra-common.ps1')
function Assert([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "Test failed: $Name" }
    Write-Host "[PASS] $Name"
}
function Expect-Failure([scriptblock]$Operation, [string]$Pattern, [string]$Name) {
    try { & $Operation; throw 'EXPECTED_EXCEPTION_WAS_NOT_THROWN' }
    catch { Assert ($_.Exception.Message -match $Pattern) $Name }
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$unicode = ([string][char]0x6570) + [char]0x5b57 + [char]0x9a6c + [char]0x529b
$argument = 'a "quoted" C:\' + $unicode + '\'
$sql = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ('minipay_identity','seata');`n"
$result = Invoke-InfraProcess powershell.exe @('-NoProfile','-File',(Join-Path $PSScriptRoot 'echo-input.ps1'),'-Value',$argument) $sql
Assert ($result.ExitCode -eq 0) 'native process succeeds under Windows PowerShell 5.1'
$payload = $result.Output | ConvertFrom-Json
Assert ($payload.value -ceq $argument) 'spaces, embedded quotes, Unicode and trailing backslash preserved'
Assert ($payload.input -ceq $sql) 'SQL quotes and newline reach stdin unchanged'
if ($BashPath) {
    $literal = 'quote '' double " dollar $() backtick ` ' + $unicode
    $shell = New-InfraShellInput @{ VALUE=$literal } 'printf %s "$VALUE"'
    $result = Invoke-InfraProcess $BashPath @('--noprofile','--norc','-s') $shell
    Assert ($result.ExitCode -eq 0 -and $result.Output -ceq $literal) 'Bash receives literal values without command substitution or quote loss'
    foreach ($path in @('scripts/k3s/checks/mysql.sh','deploy/compose-infra/health/mysql.sh','deploy/compose-infra/init/minipay-bootstrap.sh')) {
        $result = Invoke-InfraProcess $BashPath @('-n',$path) -Directory $repo
        Assert ($result.ExitCode -eq 0) ("Bash syntax: " + $path)
    }
}
$result = Invoke-InfraProcess powershell.exe @('-NoProfile','-File',(Join-Path $PSScriptRoot 'echo-input.ps1'),'-Fail')
Assert ($result.ExitCode -eq 42) 'native failure exit code retained'
Expect-Failure { Assert-InfraResult $result 'synthetic test' @{ DB_PASSWORD='synthetic-secret' } } '\[REDACTED\]' 'secret redacted in failure report'
$tmp = [System.IO.Path]::GetTempFileName()
try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($tmp, "VALUE=$unicode`nPASSWORD=dummy`n", $utf8)
    $parsed = Read-InfraEnvironment $tmp
    Assert ($parsed.VALUE -ceq $unicode) 'UTF-8 environment paths decode correctly'
    [System.IO.File]::WriteAllText($tmp, "VALUE=one`nVALUE=two`n", $utf8)
    Expect-Failure { Read-InfraEnvironment $tmp } 'Duplicate environment key' 'duplicate variable rejected'
    [System.IO.File]::WriteAllText($tmp, 'VALUE=${OTHER}', $utf8)
    Expect-Failure { Read-InfraEnvironment $tmp } 'expressions are not supported' 'ambiguous interpolation rejected'
}
finally { [System.IO.File]::Delete($tmp) }
$privateFile = Join-Path $repo 'deploy\compose-infra\.env.local'
if (Test-Path -LiteralPath $privateFile) {
    $before = (Get-FileHash -LiteralPath $privateFile -Algorithm SHA256).Hash
    $importer = Join-Path $repo 'scripts\k3s\import-local-secrets.ps1'
    $preserve = Invoke-InfraProcess powershell.exe @('-NoProfile','-File',$importer)
    Assert ($preserve.ExitCode -eq 0 -and $preserve.Output -match 'preserved') 'existing credential setup is idempotent'
    $force = Invoke-InfraProcess powershell.exe @('-NoProfile','-File',$importer,'-Force')
    Assert ($force.ExitCode -ne 0) 'Force cannot rotate initialized credentials'
    Assert ((Get-FileHash -LiteralPath $privateFile -Algorithm SHA256).Hash -ceq $before) 'private file bytes unchanged after both attempts'
    $environment = Read-InfraEnvironment $privateFile
    $broken = $environment.Clone()
    $broken.IDENTITY_DB_PASSWORD = 'change-me'
    Expect-Failure { Test-InfraVariables $broken } 'Placeholder' 'placeholder password rejected'
    $broken = $environment.Clone()
    $broken.YSHOP_MYSQL_HOST_PORT = $broken.MINIPAY_MYSQL_HOST_PORT
    Expect-Failure { Test-InfraVariables $broken } 'Duplicate host port' 'duplicate port rejected'
}
$scripts = @(Get-ChildItem (Join-Path $repo 'scripts\k3s') -Filter '*.ps1' -Recurse)
foreach ($script in $scripts) {
    $tokens = $null; $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$tokens, [ref]$errors)
    Assert ($errors.Count -eq 0) ("PowerShell parser: " + $script.Name)
}
Write-Host '[PASS] Infra regression tests finished. No containers, namespaces or database records were changed.'
