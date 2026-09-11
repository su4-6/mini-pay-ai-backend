# One-command local bring-up for MiniPay on K3s.
#
# Chains the four secret/config generators in the right order, starts the
# middleware, verifies both overlays render, then applies them in the order that
# actually works -- and waits for the workloads to become ready.
#
# Why an entry script is needed
# -----------------------------
# A fresh clone cannot be brought up by "filling in env": the four inputs are
# gitignored and were previously produced by three unrelated scripts, none of
# which wrote overlays/local/runtime.env. On top of that the apply order matters:
#
#   overlays/local         defines minipay-environment (from runtime.env).
#   overlays/digest-pinned defines only minipay-runtime. It references
#                          minipay-environment in 20+ places but never creates it,
#                          so applying it alone to a fresh cluster fails with
#                          CreateContainerConfigError.
#
# Therefore: apply local FIRST (creates the ConfigMap), then digest-pinned
# (final config + pinned images). Applying digest-pinned last is intentional --
# it re-applies base, which is the configuration the verified environment runs.
[CmdletBinding()]
param(
    [string]$InfraHost = "192.168.65.254",
    [int]$MinipayMysqlPort = 13306,
    [int]$YshopMysqlPort = 13307,
    [int]$RedisPort = 16379,
    [int]$YshopRedisPort = 16380,
    [int]$RabbitmqPort = 15673,
    [string]$GatewayPort = "18080",
    [string]$Domain = "minipay.localhost",
    [switch]$SkipInfra,
    [switch]$SkipApply,
    [switch]$Force,
    [switch]$Check
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Stage($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Ok($m) { Write-Host "  OK  $m" -ForegroundColor Green }
function Warn2($m) { Write-Host "  !!  $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "  XX  $m" -ForegroundColor Red; exit 1 }

function Invoke-Script([string]$Path, [string[]]$Arguments) {
    $exe = if (Get-Command pwsh -ErrorAction SilentlyContinue) { 'pwsh' } else { 'powershell' }
    & $exe -NoProfile -ExecutionPolicy Bypass -File $Path @Arguments
    if ($LASTEXITCODE -ne 0) { Fail "$([IO.Path]::GetFileName($Path)) 失败（exit $LASTEXITCODE）" }
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scriptDir = Join-Path $repoRoot "scripts/k3s"
$infraDir = Join-Path $repoRoot "deploy/compose-infra"
$localOverlay = Join-Path $repoRoot "deploy/k3s/overlays/local"
$pinnedOverlay = Join-Path $repoRoot "deploy/k3s/overlays/digest-pinned"

Stage '0) 前置检查'
foreach ($tool in @('docker', 'kubectl')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Fail "缺少 $tool（请先安装并加入 PATH）" }
    Ok "$tool"
}
if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    Warn2 'PATH 中没有 openssl —— create-secrets.ps1 需要它来生成 JWT 密钥对'
}
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'deploy/k3s'))) { Fail "仓库根目录判断错误：$repoRoot" }
Ok "repoRoot = $repoRoot"

Stage '1) 生成中间件口令（.env.local）'
if ((Test-Path (Join-Path $infraDir '.env.local')) -and -not $Force) {
    Ok '.env.local 已存在，保留（如需重建请加 -Force）'
} else {
    Invoke-Script (Join-Path $scriptDir 'import-local-secrets.ps1') @()
}
Ok '中间件口令就绪'

Stage '2) 生成 Identity 私密配置与 JWT 密钥对'
Invoke-Script (Join-Path $scriptDir 'create-secrets.ps1') @('-Environment', 'Local')
Ok 'identity.env + jwt-*.pem 就绪'

Stage '3) 生成各服务私密配置'
Invoke-Script (Join-Path $scriptDir 'prepare-local-workload-secrets.ps1') @()
Ok 'private/*.env 就绪'

Stage '4) 生成 local overlay 的 runtime.env'
$runtimeEnv = Join-Path $localOverlay 'runtime.env'
if ((Test-Path $runtimeEnv) -and -not $Force) {
    Ok 'runtime.env 已存在，保留（如需重建请加 -Force）'
} else {
    Invoke-Script (Join-Path $scriptDir 'prepare-local-runtime-env.ps1') @(
        '-InfraHost', $InfraHost,
        '-MinipayMysqlPort', "$MinipayMysqlPort",
        '-YshopMysqlPort', "$YshopMysqlPort",
        '-RedisPort', "$RedisPort",
        '-YshopRedisPort', "$YshopRedisPort",
        '-RabbitmqPort', "$RabbitmqPort",
        '-GatewayPort', $GatewayPort,
        '-Domain', $Domain,
        '-SkipVerify'
    )
}
Ok 'runtime.env 就绪'

Stage '5) 校验两个 overlay 都能渲染'
foreach ($overlay in @($localOverlay, $pinnedOverlay)) {
    $rendered = & kubectl kustomize $overlay 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "$overlay 渲染失败：$(($rendered | Select-Object -First 1))" }
    Ok "$(Split-Path $overlay -Leaf)（$($rendered.Count) 行）"
}
# The apply order is only safe because local defines the ConfigMap digest-pinned
# references. Re-assert that, so a future refactor cannot silently break it.
# Match per YAML document: one lazy regex over the whole render false-positives
# by spanning from minipay-runtime into a later reference of minipay-environment.
function Test-DefinesConfigMap([string]$Yaml, [string]$Name) {
    foreach ($doc in ($Yaml -split '(?m)^---\s*$')) {
        if ($doc -match '(?m)^kind:\s*ConfigMap\s*$' -and
            $doc -match ("(?m)^\s+name:\s*" + [regex]::Escape($Name) + "\s*$")) {
            return $true
        }
    }
    return $false
}
$localRendered = (& kubectl kustomize $localOverlay 2>&1) -join "`n"
$pinnedRendered = (& kubectl kustomize $pinnedOverlay 2>&1) -join "`n"
if (-not (Test-DefinesConfigMap $localRendered 'minipay-environment')) {
    Fail 'overlays/local 未定义 minipay-environment，apply 顺序的前提不成立'
}
if (Test-DefinesConfigMap $pinnedRendered 'minipay-environment') {
    Warn2 'digest-pinned 现在自己定义了 minipay-environment，注释里的说明可能需要更新'
}
Ok 'apply 顺序前提成立：local 定义 minipay-environment，digest-pinned 只锁镜像'

if ($Check) {
    Write-Host "`n-Check 模式：仅校验，未启动中间件、未 apply。" -ForegroundColor Green
    exit 0
}

if (-not $SkipInfra) {
    Stage '6) 启动中间件（MySQL / Redis / RabbitMQ / Seata）'
    Invoke-Script (Join-Path $scriptDir 'infra.ps1') @('-Action', 'Start')
    Ok '中间件已启动'
} else {
    Stage '6) 跳过中间件启动（-SkipInfra）'
}

if (-not $SkipApply) {
    Stage '7) 按正确顺序应用清单'
    Write-Host '  -> overlays/local（先：生成 minipay-environment）'
    & kubectl apply -k $localOverlay
    if ($LASTEXITCODE -ne 0) { Fail 'apply overlays/local 失败' }
    Write-Host '  -> overlays/digest-pinned（后：最终配置 + 镜像 digest 锁定）'
    & kubectl apply -k $pinnedOverlay
    if ($LASTEXITCODE -ne 0) { Fail 'apply overlays/digest-pinned 失败' }
    Ok '清单已应用'

    Stage '8) 等待工作负载就绪'
    $deadline = (Get-Date).AddMinutes(10)
    while ($true) {
        $deployments = (& kubectl -n minipay get deploy -o json 2>$null | ConvertFrom-Json).items
        $total = $deployments.Count
        $ready = ($deployments | Where-Object { $_.status.readyReplicas -eq $_.spec.replicas }).Count
        Write-Host "  $ready/$total 就绪"
        if ($total -gt 0 -and $ready -eq $total) { break }
        if ((Get-Date) -gt $deadline) { Warn2 "等待超时（$ready/$total）。查看：kubectl -n minipay get pods"; break }
        Start-Sleep -Seconds 10
    }
} else {
    Stage '7) 跳过 apply（-SkipApply）'
}

Stage '完成'
Write-Host @"
访问方式（本地是 kubectl port-forward，不是部署形态）：
  kubectl -n minipay port-forward svc/minipay-gateway-nginx $GatewayPort`:80
然后按 Host 访问：
  http://ops.$Domain`:$GatewayPort/         运营端   13800138000 / MiniPay@123456
  http://merchant.$Domain`:$GatewayPort/    商户端   13900000009 / MiniPay@123456
  http://admin.$Domain`:$GatewayPort/       管理端   13800138000 / MiniPay@123456
  http://food-admin.$Domain`:$GatewayPort/  YShop 后台 admin / admin123
  http://food.$Domain`:$GatewayPort/        外卖 H5
"@ -ForegroundColor Green
