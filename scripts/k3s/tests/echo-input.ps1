param([string]$Value, [switch]$Fail)
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$inputText = [Console]::In.ReadToEnd()
if ($Fail) { [Console]::Error.WriteLine('synthetic-secret'); exit 42 }
@{ value=$Value; input=$inputText } | ConvertTo-Json -Compress
