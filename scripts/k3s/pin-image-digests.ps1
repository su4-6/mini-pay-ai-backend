# 依据 deploy/k3s/generated/image-digests.json，把 workloads 清单里的镜像改成
# registry/repo@sha256:... 形式（digest 锁定），写入 kustomize overlay，不改动 base。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/k3s/pin-image-digests.ps1
param(
    [string]$DigestFile,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path (Join-Path $repoRoot 'deploy\k3s'))) {
    $repoRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
}
if (-not $DigestFile) { $DigestFile = Join-Path $repoRoot 'deploy\k3s\generated\image-digests.json' }
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'deploy\k3s\overlays\digest-pinned' }
$workloadDir = Join-Path $repoRoot 'deploy\k3s\base\workloads'

if (-not (Test-Path $DigestFile)) { throw "digest 文件不存在: $DigestFile（先运行 push-and-record-digests.ps1）" }

$digests = (Get-Content $DigestFile -Raw | ConvertFrom-Json).digests
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "==> 生成 digest 锁定 patch（$($digests.PSObject.Properties.Count) 个镜像）" -ForegroundColor Cyan

# 每个 Deployment 生成一个 JSON6902 patch，只替换 container 的 image 字段
$patches = @()
$count = 0
foreach ($f in (Get-ChildItem $workloadDir -File -Filter '*.yaml')) {
    $lines = Get-Content $f.FullName -Encoding UTF8
    $depName = $null; $img = $null
    foreach ($line in $lines) {
        if (-not $depName -and $line -match '^\s{2}name:\s*(\S+)\s*$') { $depName = $Matches[1] }
        if (-not $img -and $line -match '^\s+image:\s*(\S+)\s*$') { $img = $Matches[1] }
    }
    if (-not $depName -or -not $img) { continue }
    $digest = $digests.$img
    if (-not $digest) { Write-Host "  跳过（无 digest 记录）: $img" -ForegroundColor DarkGray; continue }

    $repo = $img.Substring(0, $img.LastIndexOf(':'))
    $pinned = "$repo@$digest"

    $patchName = "$depName-image.json"
    # ⚠️ JSON6902 patch 必须是数组。PS 5.1 的 ConvertTo-Json 会把单元素数组拆成对象，
    # 导致 kustomize 报 "unable to parse SM or JSON patch"，因此手工拼 JSON。
    $patch = '[{"op":"replace","path":"/spec/template/spec/containers/0/image","value":"' + $pinned + '"}]'
    # ⚠️ 必须无 BOM：kustomize 解析带 BOM 的 JSON patch 会失败
    [IO.File]::WriteAllText((Join-Path $OutDir $patchName), $patch, [Text.UTF8Encoding]::new($false))
    $patches += $patchName
    $count++
    Write-Host ("  {0,-22} → {1}" -f $depName, $pinned.Substring(0, [Math]::Min(70, $pinned.Length)) + '...')
}

# kustomization：引用 base 并对每个 Deployment 打 patch
$ks = @()
$ks += 'apiVersion: kustomize.config.k8s.io/v1beta1'
$ks += 'kind: Kustomization'
$ks += 'namespace: minipay'
$ks += 'resources:'
$ks += '  - ../../base'
$ks += ''
$ks += '# 由 scripts/k3s/pin-image-digests.ps1 生成：把所有自建镜像固定到 registry digest。'
$ks += '# 生产部署应使用本 overlay，保证「同一清单永远拉到同一镜像」。'
$ks += 'patches:'
foreach ($p in $patches) {
    $ks += "  - path: $p"
    $ks += '    target:'
    $ks += "      kind: Deployment"
    $ks += "      name: $($p -replace '-image\.json$','')"
}
Set-Content -Path (Join-Path $OutDir 'kustomization.yaml') -Value ($ks -join "`n") -Encoding UTF8

Write-Host "已生成 $count 个 patch → $OutDir" -ForegroundColor Green
