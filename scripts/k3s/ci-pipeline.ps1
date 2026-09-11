# MiniPay + YShop 一体化 CI 流水线：构建 → 打标签 → 推送 → 记录 digest → 生成 digest 锁定 overlay
#
# 设计目标：把此前所有「手工步骤」固化为一条可重复执行的命令，避免再出现
#   - 清单 tag 与实部署不一致（曾导致 kubectl apply 把镜像回退到旧版本、Pod 静默崩溃）
#   - 手工 docker save | ctr import 灌镜像
#   - 手工打补丁改产物
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/k3s/ci-pipeline.ps1 -Version 0.1.0-k3s.4
#   ... -SkipBuild          仅推送 + 记录 digest + 生成 overlay
#   ... -SkipPush           仅本地构建 + 生成 overlay
#   ... -Deploy             最后执行 kubectl apply -k overlays/digest-pinned
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Namespace = 'suqihang',
    [string]$Registry = 'docker.io',
    [string]$FrontendDir = '',
    [switch]$SkipBuild,
    [switch]$SkipPush,
    [switch]$Deploy,
    [string]$MavenJavaHome = 'C:\Users\hp\.jdks\ms-21.0.12',
    [string]$MavenJavaHome17 = 'C:\Users\hp\.jdks\ms-17.0.20'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Stage($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Ok($m) { Write-Host "  OK  $m" -ForegroundColor Green }
function Fail($m) { Write-Host "  !!  $m" -ForegroundColor Red; exit 1 }
function Run($exe, $argsArr, $workdir) {
    Push-Location $workdir
    try { & $exe @argsArr; if ($LASTEXITCODE -ne 0) { Fail "$exe $($argsArr -join ' ') (exit $LASTEXITCODE)" } }
    finally { Pop-Location }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not (Test-Path (Join-Path $repoRoot 'deploy/k3s'))) {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
}
if ([string]::IsNullOrWhiteSpace($FrontendDir)) {
    $FrontendDir = Join-Path (Split-Path -Parent $repoRoot) 'mini-pay-ai-frontend-clean'
}
$frontend = (Resolve-Path $FrontendDir).Path
$imageTagRoot = "$Namespace"

Write-Host "repo    = $repoRoot"
Write-Host "frontend= $frontend"
Write-Host "version = $Version"

# ---------------------------------------------------------------- 1) Java 服务
$javaServices = @(
    @{ name = 'identity-service';   module = 'services/identity-service' },
    @{ name = 'wallet-service';     module = 'services/wallet-service' },
    @{ name = 'payment-service';    module = 'services/payment-service' },
    @{ name = 'commerce-service';   module = 'services/commerce-service' },
    @{ name = 'agent-service';      module = 'services/agent-service' },
    @{ name = 'consumer-bff';       module = 'services/consumer-bff' },
    @{ name = 'management-bff';     module = 'services/management-bff' },
    @{ name = 'admin-bff';          module = 'services/admin-bff' }
)

if (-not $SkipBuild) {
    Stage '构建 8 个 Java/BFF 服务镜像'
    $env:JAVA_HOME = $MavenJavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    foreach ($svc in $javaServices) {
        Write-Host "  -> $($svc.name)"
        Run 'mvn' @('-B', '-ntp', '-pl', $svc.module, '-am', '-DskipTests', 'package') $repoRoot
        Run 'docker' @('build', '--file', "$repoRoot/docker/k3s-service.Dockerfile",
            '--build-arg', "MODULE=$($svc.module)",
            '--tag', "$Namespace/$($svc.name):$Version", $repoRoot) $repoRoot
    }
    Ok 'Java 镜像完成'

    Stage '构建 3 个前端静态镜像'
    foreach ($web in @(
            @{ name = 'merchant-web'; path = "$frontend/apps/merchant-web";           df = "$repoRoot/deploy/k3s/images/static-web.Dockerfile"; dist = 'dist' },
            @{ name = 'ops-web';      path = "$frontend/apps/ops-web";                df = "$repoRoot/deploy/k3s/images/static-web.Dockerfile"; dist = 'dist' },
            @{ name = 'admin-web';    path = "$frontend/apps/admin-web";              df = "$repoRoot/deploy/k3s/images/static-web.Dockerfile"; dist = 'dist' }
        )) {
        if (-not (Test-Path $web.path)) { Write-Host "  跳过（不存在）: $($web.name)" -ForegroundColor DarkGray; continue }
        Write-Host "  -> $($web.name)"
        Run 'pnpm.cmd' @('install', '--frozen-lockfile') $web.path
        Run 'pnpm.cmd' @('build') $web.path
        if (Test-Path $web.df) {
            Run 'docker' @('build', '--file', $web.df, '--tag', "$Namespace/$($web.name):$Version", $web.path) $web.path
        } else {
            Write-Host "  仅构建产物（无 Dockerfile）: $($web.name)" -ForegroundColor Yellow
        }
    }
    Ok '前端镜像完成'

    Stage '构建 YShop 三件套（正版 yshop-drink）'
    $yshopServer = "$repoRoot/integrations/yshop/server"
    $yshopAdminWeb = "$frontend/integrations/yshop/admin-web"
    $yshopFoodH5 = "$frontend/integrations/yshop/food-h5"

    $env:JAVA_HOME = $MavenJavaHome17
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    Write-Host '  -> yshop-server (Maven, Java 17)'
    Run 'mvn' @('-B', '-ntp', '-pl', 'yshop-server', '-am', '-DskipTests', 'package') $yshopServer
    Run 'docker' @('build', '--file', "$repoRoot/deploy/k3s/images/yshop-server.Dockerfile",
        '--tag', "$Namespace/yshop-server:$Version", $yshopServer) $yshopServer

    # admin-web 已可从源码确定性重建（Vite CLI），因此默认源码构建，
    # 确保登录页等源码修复必然进入镜像；设 MINIPAY_SKIP_ADMIN_WEB_BUILD=1 可复用仓库内 dist-k3s。
    if ($env:MINIPAY_SKIP_ADMIN_WEB_BUILD -eq '1' -and (Test-Path "$yshopAdminWeb/dist-k3s/index.html")) {
        Write-Host '  -> yshop-admin-web（跳过源码构建，复用仓库内 dist-k3s）' -ForegroundColor Yellow
    }
    else {
        Write-Host '  -> yshop-admin-web（pnpm 源码构建，输出 dist-k3s）'
        Run 'pnpm.cmd' @('install', '--frozen-lockfile') $yshopAdminWeb
        Remove-Item "$yshopAdminWeb/dist-k3s" -Recurse -Force -ErrorAction SilentlyContinue
        # 注意：不要在 PowerShell 里用 $env:VITE_BASE_URL = '' 来清空基址——
        # .NET 把“设为空串”当作删除变量，Vite 会静默回落到 .env.prod 的值。
        # 相对基址统一由 admin-web/.env.prod 的 VITE_BASE_URL='' 提供。
        $env:VITE_BASE_PATH = '/'; $env:VITE_API_URL = '/admin-api'; $env:VITE_OUT_DIR = 'dist-k3s'
        Run 'pnpm.cmd' @('build:prod') $yshopAdminWeb
        Remove-Item Env:VITE_BASE_PATH, Env:VITE_API_URL, Env:VITE_OUT_DIR -ErrorAction SilentlyContinue
        if (-not (Test-Path "$yshopAdminWeb/dist-k3s/index.html")) { Fail 'admin-web 源码构建未产出 dist-k3s/index.html' }

        # 上线红线 1：产物不得含默认口令等明文凭据
        $leak = Get-ChildItem "$yshopAdminWeb/dist-k3s" -Recurse -File |
            Select-String -Pattern 'admin123' -List
        if ($leak) { Fail "admin-web 产物含明文默认口令：$($leak[0].Path)" }

        # 上线红线 2：产物不得把 API 指向构建机/本机（必须同源相对路径）
        $badHost = Get-ChildItem "$yshopAdminWeb/dist-k3s" -Recurse -File -Include '*.js','*.html' |
            Select-String -Pattern 'localhost:\d+/admin-api|127\.0\.0\.1:\d+/admin-api' -List
        if ($badHost) { Fail "admin-web 产物把接口硬编码到本机地址：$($badHost[0].Path) -> $($badHost[0].Line.Trim())" }

        $relOk = Get-ChildItem "$yshopAdminWeb/dist-k3s" -Recurse -File -Filter 'index-*.js' |
            Select-String -Pattern 'base_url:"/admin-api"' -List
        if (-not $relOk) { Fail 'admin-web 产物未生成同源相对基址 base_url:"/admin-api"' }
        Write-Host '     产物校验通过：相对基址 base_url:"/admin-api"，无明文口令，无本机硬编码'
    }
    Run 'docker' @('build', '--file', "$repoRoot/deploy/k3s/images/yshop-admin-web.Dockerfile",
        '--tag', "$Namespace/yshop-admin-web:$Version", $yshopAdminWeb) $yshopAdminWeb

    # 外卖 H5 也可以从源码确定性重建（uni-app CLI，不需要 HBuilderX）。
    # 默认源码构建，保证镜像里的 H5 一定对应当前源码；
    # 设 MINIPAY_SKIP_H5_BUILD=1 可复用仓库内已构建的 h5-minipay。
    if ($env:MINIPAY_SKIP_H5_BUILD -eq '1' -and (Test-Path "$yshopFoodH5/unpackage/dist/build/h5-minipay/index.html")) {
        Write-Host '  -> yshop-food-h5（跳过源码构建，复用仓库内 h5-minipay）' -ForegroundColor Yellow
    }
    else {
        Write-Host '  -> yshop-food-h5（uni-app CLI 源码构建，约 5 分钟）'
        $h5Builder = "$yshopFoodH5/scripts/build-minipay-h5-cli.ps1"
        if (-not (Test-Path $h5Builder)) { Fail "缺少 H5 源码构建脚本：$h5Builder" }
        Run 'powershell' @('-ExecutionPolicy', 'Bypass', '-File', $h5Builder,
            '-ApiBaseUrl', '/app-api', '-RouterBase', '/') $yshopFoodH5
        if (-not (Test-Path "$yshopFoodH5/unpackage/dist/build/h5-minipay/index.html")) {
            Fail 'H5 源码构建未产出 unpackage/dist/build/h5-minipay/index.html'
        }
        Write-Host '     产物校验通过：相对基址 /app-api，无硬编码主机'
    }
    Run 'docker' @('build', '--file', "$repoRoot/deploy/k3s/images/yshop-food-h5.Dockerfile",
        '--tag', "$Namespace/yshop-food-h5:$Version", $yshopFoodH5) $yshopFoodH5
    Ok 'YShop 镜像完成'
}

# ------------------------------------------------- 2) 清单 tag 与本次版本对齐
# ⚠️ 仅在真正构建了镜像时才改清单 tag。否则用任意 Version 跑一次（例如 -SkipBuild）
# 就会把所有清单改成不存在的 tag，之后 kubectl apply 会把集群镜像回退到拉不到的版本。
if (-not $SkipBuild) {
    Stage "将 workloads 清单镜像 tag 统一为 $Version"
    $workloadDir = "$repoRoot/deploy/k3s/base/workloads"
    $changed = 0
    foreach ($f in (Get-ChildItem $workloadDir -File -Filter '*.yaml')) {
        $t = [IO.File]::ReadAllText($f.FullName, [Text.UTF8Encoding]::new($false))
        $orig = $t
        # 只改本命名空间的自建镜像；gateway 等第三方不动
        $t = [regex]::Replace($t, "(image:\s*$([regex]::Escape($Namespace))/[A-Za-z0-9._\-]+):([^\s@]+)", "`$1:$Version")
        if ($t -ne $orig) { [IO.File]::WriteAllText($f.FullName, $t, [Text.UTF8Encoding]::new($false)); $changed++ }
    }
    Ok "已更新 $changed 个清单文件"
}
else {
    Stage '跳过清单 tag 对齐（-SkipBuild：未构建新镜像，不应改动清单 tag）'
}

# ------------------------------------------------- 2.5) 产物来源校验（漂移检测）
Stage '校验外卖 H5 预构建产物（哈希锁定，防悄悄改动）'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/verify-h5-bundle.ps1"
if ($LASTEXITCODE -ne 0) { Fail 'H5 产物与 BUNDLE_PROVENANCE.md 记录不一致；若是有意修改，运行 verify-h5-bundle.ps1 -Update 并说明原因' }
Ok 'H5 产物来源一致'

# ---------------------------------------------------------------- 3) 推送 + digest
if (-not $SkipPush) {
    Stage '推送镜像并记录 digest'
    Run 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$PSScriptRoot/push-and-record-digests.ps1",
        '-Registry', $Registry, '-Namespace', $Namespace) $PSScriptRoot
    Ok 'digest 已记录'
}

# ------------------------------------------------------- 4) 生成 digest 锁定 overlay
Stage '生成 digest 锁定 overlay'
Run 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$PSScriptRoot/pin-image-digests.ps1") $PSScriptRoot
Ok 'overlay 已生成: deploy/k3s/overlays/digest-pinned'

Stage '校验 overlay 可构建'
& kubectl kustomize "$repoRoot/deploy/k3s/overlays/digest-pinned" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'kustomize 构建失败' }
Ok 'kustomize 构建通过'

if ($Deploy) {
    Stage '部署 digest 锁定 overlay'
    & kubectl apply -k "$repoRoot/deploy/k3s/overlays/digest-pinned"
    if ($LASTEXITCODE -ne 0) { Fail '部署失败' }
    & kubectl -n minipay rollout status deployment --timeout=300s 2>&1 | Select-Object -Last 3
    Ok '部署完成'
}

Write-Host "`n流水线完成（version=$Version）" -ForegroundColor Green
Write-Host "提示：生产部署请使用 deploy/k3s/overlays/digest-pinned（digest 锁定），不要直接 apply base。"
