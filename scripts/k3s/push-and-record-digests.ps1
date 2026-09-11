# 推送全部 K8s 自建镜像到 registry，并记录每个 tag 的 digest。
# 输出：deploy/k3s/generated/image-digests.json（供清单按 digest 锁定）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/k3s/push-and-record-digests.ps1
#   powershell ... -File ... -Registry docker.io -Namespace suqihang -DryRun
param(
    [string]$Registry = 'docker.io',
    [string]$Namespace = 'suqihang',
    [string[]]$Only = @(),
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path (Join-Path $repoRoot 'deploy\k3s'))) {
    $repoRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
}
$workloadDir = Join-Path $repoRoot 'deploy\k3s\base\workloads'
$outDir = Join-Path $repoRoot 'deploy\k3s\generated'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

# 1) 从清单里收集所有自建镜像（排除第三方，如 ghcr.io/nginx/...）
Write-Step "从 deploy/k3s/base/workloads 收集自建镜像"
$images = @()
foreach ($f in (Get-ChildItem $workloadDir -File -Filter '*.yaml')) {
    $img = $null
    foreach ($line in (Get-Content $f.FullName -Encoding UTF8)) {
        if (-not $img -and $line -match '^\s+image:\s*(\S+)\s*$') { $img = $Matches[1] }
    }
    if (-not $img) { continue }
    # 只处理本命名空间的镜像；ghcr.io/nginx/... 之类跳过
    if ($img -notmatch "^$([regex]::Escape($Namespace))/") { Write-Host "  跳过第三方: $img" -ForegroundColor DarkGray; continue }
    $images += $img
}
$images = $images | Sort-Object -Unique
# -File 调用时 PowerShell 会把 "a,b" 当成单个字符串，这里按逗号再拆一次
$onlyFlat = @()
foreach ($o in $Only) { $onlyFlat += ($o -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
if ($onlyFlat.Count -gt 0) {
    $images = $images | Where-Object { $n = $_; ($onlyFlat | Where-Object { $n -like "*$_*" }).Count -gt 0 }
}

Write-Host "  待推送: $($images.Count) 个"

# 2) 推送
Write-Step "推送镜像"
$failed = @()
foreach ($img in $images) {
    if ($DryRun) { Write-Host "  [DRY] docker push $img"; continue }
    Write-Host "  pushing $img ..."
    $out = & docker push $img 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ❌ 失败: $img" -ForegroundColor Red
        $out | Select-Object -Last 3 | ForEach-Object { Write-Host "     $_" -ForegroundColor DarkGray }
        $failed += $img
    } else {
        $d = ($out | Select-String -Pattern 'digest:\s*(sha256:[0-9a-f]+)' | Select-Object -Last 1)
        if ($d) { Write-Host "  ✅ $img  $($d.Matches[0].Groups[1].Value.Substring(0,19))..." -ForegroundColor Green }
        else { Write-Host "  ✅ $img" -ForegroundColor Green }
    }
}

# 3) 从本地镜像读取 RepoDigest 并写出 digest 映射
Write-Step "记录 digest"
$map = [ordered]@{}
foreach ($img in $images) {
    if ($failed -contains $img) { continue }
    $repo = $img.Substring(0, $img.LastIndexOf(':'))
    $tag = $img.Substring($img.LastIndexOf(':') + 1)
    $digest = $null
    $info = & docker image inspect $img --format '{{range .RepoDigests}}{{println .}}{{end}}' 2>$null
    if ($info) {
        $match = ($info | Where-Object { $_ -match [regex]::Escape($repo) + '@(sha256:[0-9a-f]+)' } | Select-Object -First 1)
        if ($match -and $match -match '@(sha256:[0-9a-f]+)') { $digest = $Matches[1] }
    }
    if ($digest) {
        $map[$img] = $digest
        Write-Host ("  {0,-52} {1}" -f $img, $digest.Substring(0, 19) + '...')
    } else {
        Write-Host ("  ⚠️ {0} 无 RepoDigest（可能未推送成功）" -f $img) -ForegroundColor Yellow
    }
}

if (-not $DryRun) {
    $file = Join-Path $outDir 'image-digests.json'
    # 与已有记录合并：增量重推（-Only）时不能把之前已记录的 digest 抹掉
    $merged = [ordered]@{}
    if (Test-Path $file) {
        $prev = Get-Content $file -Raw | ConvertFrom-Json
        foreach ($p in $prev.digests.PSObject.Properties) { $merged[$p.Name] = $p.Value }
    }
    # 本次失败或被移除的镜像不应保留旧 digest
    if ($failed.Count -gt 0) { foreach ($f in $failed) { if ($merged.Contains($f)) { $merged.Remove($f) } } }
    foreach ($k in $map.Keys) { $merged[$k] = $map[$k] }

    $payload = [ordered]@{
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        registry    = $Registry
        namespace   = $Namespace
        digests     = $merged
    }
    $payload | ConvertTo-Json -Depth 5 | Set-Content -Path $file -Encoding UTF8
    Write-Step "已写出 $file（共 $($merged.Count) 条）"
}

if ($failed.Count -gt 0) { Write-Host "失败 $($failed.Count) 个" -ForegroundColor Red; exit 1 }
Write-Host "全部完成" -ForegroundColor Green
