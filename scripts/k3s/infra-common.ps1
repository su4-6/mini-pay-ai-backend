# Windows PowerShell 5.1 compatible. Dot-source only; no changes on import.
Set-StrictMode -Version Latest

function ConvertTo-NativeArgument([string]$Value) {
    # Windows CRT quoting, including embedded quotes and trailing backslashes.
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-InfraProcess {
    param([string]$File, [string[]]$Arguments, [string]$InputText = '',
          [int]$TimeoutSeconds = 60, [string]$Directory = (Get-Location).Path)
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Command $File -ErrorAction Stop).Source
    $info.Arguments = (($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' ')
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if ($InputText.Length -gt 0) {
            # .NET Framework (PowerShell 5.1) lacks StandardInputEncoding.
            $inputBytes = $utf8.GetBytes($InputText.Replace("`r`n", "`n"))
            $process.StandardInput.BaseStream.Write($inputBytes, 0, $inputBytes.Length)
            $process.StandardInput.BaseStream.Flush()
        }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw "Timed out waiting for $File. Existing containers and volumes were not deleted."
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $outputTask.GetAwaiter().GetResult()
            ErrorText = $errorTask.GetAwaiter().GetResult()
        }
    }
    finally { $process.Dispose() }
}

function Read-InfraEnvironment([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'Private .env.local is missing. Run import-local-secrets.ps1 for first-time setup.'
    }
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
            throw 'Invalid environment line. Use KEY=value without multiline or shell expressions.'
        }
        $key = $Matches[1]
        $value = $Matches[2]
        if ($values.ContainsKey($key)) { throw "Duplicate environment key: $key" }
        if ($value -match '^".*"$|^''.*''$') { $value = $value.Substring(1, $value.Length - 2) }
        if ($value.Contains('${') -or $value.Contains('`')) {
            throw "Environment expressions are not supported for $key. Use a literal value."
        }
        $values[$key] = $value
    }
    return $values
}

function Protect-InfraOutput([string]$Text, [hashtable]$Environment) {
    foreach ($key in $Environment.Keys) {
        if ($key -match 'PASSWORD|SECRET|TOKEN|PRIVATE_KEY' -and $Environment[$key]) {
            $Text = $Text.Replace([string]$Environment[$key], '[REDACTED]')
        }
    }
    return $Text
}

function Assert-InfraResult($Result, [string]$Step, [hashtable]$Environment, [switch]$ShowOutput) {
    if ($Result.ExitCode -ne 0) {
        $detail = Protect-InfraOutput ($Result.Output + $Result.ErrorText) $Environment
        throw "$Step failed (exit $($Result.ExitCode)).`n$detail"
    }
    if ($ShowOutput -and $Result.Output.Trim()) {
        Write-Host (Protect-InfraOutput $Result.Output.Trim() $Environment)
    }
}

function ConvertTo-ShellLiteral([string]$Value) {
    $singleQuote = [string][char]39
    $doubleQuote = [string][char]34
    $replacement = $singleQuote + $doubleQuote + $singleQuote + $doubleQuote + $singleQuote
    return $singleQuote + $Value.Replace($singleQuote, $replacement) + $singleQuote
}

function New-InfraShellInput([hashtable]$Variables, [string]$Body) {
    $lines = @('set -eu')
    foreach ($key in ($Variables.Keys | Sort-Object)) {
        if ($key -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid shell variable name.' }
        $lines += 'export ' + $key + '=' + (ConvertTo-ShellLiteral ([string]$Variables[$key]))
    }
    return ($lines -join "`n") + "`n" + $Body.Replace("`r`n", "`n") + "`n"
}

function Test-InfraVariables([hashtable]$Environment) {
    $required = @('INFRA_BIND_HOST','MINIPAY_MYSQL_HOST_PORT','MINIPAY_REDIS_HOST_PORT',
        'RABBITMQ_HOST_PORT','RABBITMQ_MANAGEMENT_HOST_PORT','SEATA_SERVICE_HOST_PORT',
        'YSHOP_MYSQL_HOST_PORT','YSHOP_REDIS_HOST_PORT','MINIPAY_MYSQL_ROOT_PASSWORD',
        'MINIPAY_REDIS_PASSWORD','RABBITMQ_USERNAME','RABBITMQ_PASSWORD',
        'YSHOP_MYSQL_DATABASE','YSHOP_MYSQL_USERNAME','YSHOP_MYSQL_PASSWORD',
        'YSHOP_MYSQL_ROOT_PASSWORD','YSHOP_REDIS_PASSWORD',
        'TURN_BIND_HOST','TURN_EXTERNAL_IP','TURN_REALM','TURN_SHARED_SECRET')
    foreach ($prefix in 'IDENTITY','PAYMENT','WALLET','COMMERCE','AGENT','SEATA') {
        $required += "${prefix}_DB_USERNAME", "${prefix}_DB_PASSWORD"
    }
    foreach ($key in $required) {
        if (-not $Environment.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($Environment[$key])) {
            throw "Missing configuration: $key"
        }
        $value = [string]$Environment[$key]
        if ($key -match 'PASSWORD|SECRET' -and ($value.Length -lt 16 -or $value -match '(?i)change-me|replace-me|demo|example')) {
            throw "Placeholder or insufficient secret: $key (value hidden)."
        }
        if ($key -match 'USERNAME$|DATABASE$' -and $value -notmatch '^[A-Za-z][A-Za-z0-9_]*$') {
            throw "Invalid account/database name in $key."
        }
    }
    if ($Environment.SEATA_DB_USERNAME -ne 'seata') { throw 'SEATA_DB_USERNAME must be seata.' }
    $usedPorts = @{}
    foreach ($key in ($required | Where-Object { $_ -match '_HOST_PORT$|SEATA_SERVICE_HOST_PORT' })) {
        $port = 0
        if (-not [int]::TryParse($Environment[$key], [ref]$port) -or $port -lt 1024 -or $port -gt 65535) {
            throw "Invalid host port: $key"
        }
        if ($usedPorts.ContainsKey($port)) { throw "Duplicate host port for $key and $($usedPorts[$port])." }
        $usedPorts[$port] = $key
    }
    # The local profile stays loopback-only until an actual Pod test proves reachability.
    if ($Environment.INFRA_BIND_HOST -ne '127.0.0.1') {
        throw 'Local INFRA_BIND_HOST must be 127.0.0.1. Do not expose databases on every interface to fix connectivity.'
    }
    foreach ($key in $Environment.Keys) {
        $inherited = [Environment]::GetEnvironmentVariable($key, 'Process')
        if ($null -ne $inherited -and $inherited -ne $Environment[$key]) {
            throw "Shell variable $key overrides .env.local. Remove that session override before continuing."
        }
    }
}

function Get-InfraMysqlVariables([hashtable]$Environment, [string]$Kind, [string]$Address, [int]$Port) {
    $vars = @{ VERIFY_KIND=$Kind; VERIFY_HOST=$Address; VERIFY_PORT=[string]$Port }
    if ($Kind -eq 'yshop') {
        $vars.MYSQL_USER = $Environment.YSHOP_MYSQL_USERNAME
        $vars.MYSQL_PASSWORD = $Environment.YSHOP_MYSQL_PASSWORD
        $vars.MYSQL_DATABASE = $Environment.YSHOP_MYSQL_DATABASE
    } else {
        foreach ($prefix in 'IDENTITY','PAYMENT','WALLET','COMMERCE','AGENT','SEATA') {
            $vars["${prefix}_DB_USERNAME"] = $Environment["${prefix}_DB_USERNAME"]
            $vars["${prefix}_DB_PASSWORD"] = $Environment["${prefix}_DB_PASSWORD"]
        }
    }
    return $vars
}
