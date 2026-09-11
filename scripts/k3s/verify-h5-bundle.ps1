# 校验外卖 H5 预构建产物的完整性，防止「悄悄改了产物但没更新文档」。
#
# 背景：integrations/yshop/food-h5 的构建链是 HBuilderX 独有的（package.json 里没有
# @dcloudio/* 依赖），CI 无法用 npm 重建，只能把产物入库并做哈希锁定。
# 详见 integrations/yshop/food-h5/BUNDLE_PROVENANCE.md
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/k3s/verify-h5-bundle.ps1          # 校验
#   powershell -ExecutionPolicy Bypass -File scripts/k3s/verify-h5-bundle.ps1 -Update  # 更新指纹
param(
    [string]$BundleDir,
    [string]$ProvenanceFile,
    [switch]$Update
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not (Test-Path (Join-Path $repoRoot 'deploy/k3s'))) {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
}
$frontend = Join-Path (Split-Path -Parent $repoRoot) 'mini-pay-ai-frontend-clean'
if (-not $BundleDir) { $BundleDir = Join-Path $frontend 'integrations/yshop/food-h5/unpackage/dist/build/h5-minipay' }
if (-not $ProvenanceFile) { $ProvenanceFile = Join-Path $frontend 'integrations/yshop/food-h5/BUNDLE_PROVENANCE.md' }

if (-not (Test-Path $BundleDir)) { throw "产物目录不存在: $BundleDir" }

function Get-BundleManifestHash([string]$dir) {
    $files = Get-ChildItem $dir -Recurse -File | Sort-Object FullName
    $lines = $files | ForEach-Object {
        $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
        $rel = $_.FullName.Replace("$dir\", '').Replace('\', '/')
        "$h  $rel"
    }
    $joined = ($lines -join "`n")
    $bytes = [Text.Encoding]::UTF8.GetBytes($joined)
    return [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-', '').ToLower()
}

Write-Host "产物目录: $BundleDir" -ForegroundColor Cyan
$count = (Get-ChildItem $BundleDir -Recurse -File | Measure-Object).Count
$hash = Get-BundleManifestHash $BundleDir
Write-Host "  文件数   : $count"
Write-Host "  清单哈希 : $hash"

# 断言：产物里不能出现硬编码的绝对 API 地址（必须走相对 /app-api，由 H5 nginx 反代）
$absHit = Get-ChildItem $BundleDir -Recurse -File -Include '*.js' |
    Select-String -Pattern 'http://food\.minipay\.localhost' -List
if ($absHit) {
    Write-Host "  ❌ 产物中出现硬编码绝对地址（应为相对 /app-api）:" -ForegroundColor Red
    $absHit | ForEach-Object { Write-Host "     $($_.Filename)" -ForegroundColor Red }
    exit 1
}
Write-Host "  绝对地址 : 无（正确）" -ForegroundColor Green

if ($Update) {
    if (-not (Test-Path $ProvenanceFile)) { throw "来源文档不存在: $ProvenanceFile" }
    $doc = [IO.File]::ReadAllText($ProvenanceFile, [Text.UTF8Encoding]::new($false))
    $doc = [regex]::Replace($doc, '(\| 文件数 \| )\d+( \|)', "`${1}$count`${2}")
    $doc = [regex]::Replace($doc, '(\| 清单总哈希 \| )`[0-9a-f]+`( \|)', "`${1}``$hash``${2}")
    [IO.File]::WriteAllText($ProvenanceFile, $doc, [Text.UTF8Encoding]::new($false))
    Write-Host "已更新指纹 → $ProvenanceFile" -ForegroundColor Green
    exit 0
}

if (-not (Test-Path $ProvenanceFile)) { Write-Host "⚠️ 找不到来源文档，跳过比对" -ForegroundColor Yellow; exit 0 }
$doc = [IO.File]::ReadAllText($ProvenanceFile, [Text.UTF8Encoding]::new($false))
$expected = $null
if ($doc -match '\| 清单总哈希 \| `([0-9a-f]{64})`') { $expected = $Matches[1] }

if (-not $expected) { Write-Host "⚠️ 来源文档中未找到哈希，运行 -Update 初始化" -ForegroundColor Yellow; exit 0 }

if ($expected -eq $hash) {
    Write-Host "  ✅ 与来源文档一致" -ForegroundColor Green
    exit 0
} else {
    Write-Host "  ❌ 产物已变更，但与来源文档不一致！" -ForegroundColor Red
    Write-Host "     文档记录: $expected"
    Write-Host "     实际计算: $hash"
    Write-Host "     若是有意修改，请运行 -Update 并说明改动原因。" -ForegroundColor Yellow
    exit 1
}
