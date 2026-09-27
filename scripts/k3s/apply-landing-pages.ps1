# Render the two landing pages from the in-repo source of truth and roll them out.
#
# Background: pay.su46proj.site and su46proj.site are served by the in-cluster
# landing-pay / landing-personal Deployments (nginx + a ConfigMap mounted as index.html).
# The HTML itself comes from integrations/cloudflare/landing-worker/src/pages.js.
# Until now that pipeline existed only outside the repo, so a "landing page fix" was a
# manual copy-paste. This script closes that gap:
#
#   1. render projectLandingPage() / personalHomePage() via node
#   2. write the result to deploy/k3s/landing/{pay,personal}.html  (checked in, reviewable)
#   3. create/update the landing-pay / landing-personal ConfigMaps
#   4. restart both Deployments and wait for the rollout
#   5. verify the CLUSTER ORIGIN no longer advertises the retired Android APK
#      (-NodeIp <public ip>; uses curl --resolve to bypass the CDN)
#
# Usage:
#   pwsh -NoProfile -File scripts/k3s/apply-landing-pages.ps1 -SkipApply          # render + diff only
#   pwsh -NoProfile -File scripts/k3s/apply-landing-pages.ps1                     # render + apply
#   pwsh -NoProfile -File scripts/k3s/apply-landing-pages.ps1 -NodeIp 122.152.221.201
#
# NOTE: both hosts sit behind an edge cache (pay = Tencent CDN, su46proj.site = Cloudflare).
# Updating the origin does NOT update what the public sees: purge the edge cache for
#   https://pay.su46proj.site/   and   https://su46proj.site/
# afterwards (console operation -- no API credential is available in this environment).
# Run scripts/k3s/acceptance-server.ps1 -OriginIp <ip> to see origin-vs-edge status.
#
# ASCII-only on purpose: PowerShell files with Chinese comments need UTF-8 *with* BOM for
# Windows PowerShell 5.1; this one avoids that trap.

param(
    [string] $Namespace = 'minipay',
    [switch] $SkipApply,
    [string] $NodeIp = '',
    [string] $LandingHost = 'pay.su46proj.site',
    [string] $PersonalHost = 'su46proj.site',
    [int] $RolloutTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$pagesJs = Join-Path $repoRoot 'integrations/cloudflare/landing-worker/src/pages.js'
$outputDir = Join-Path $repoRoot 'deploy/k3s/landing'

if (-not (Test-Path -LiteralPath $pagesJs)) { throw "landing page source not found: $pagesJs" }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'node is required to render the landing pages' }

Write-Host '== render landing pages from pages.js =='
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("minipay-landing-{0}" -f ([guid]::NewGuid().ToString('N')))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $repoRoot 'integrations/cloudflare/landing-worker/src/*') $tempDir

# pages.js uses ESM imports, so node needs an explicit module package.json next to it.
$moduleMarker = Join-Path $tempDir 'package.json'
Set-Content -LiteralPath $moduleMarker -Value '{"type":"module"}' -Encoding ASCII

$renderer = Join-Path $tempDir 'render.mjs'
$rendererBody = @'
import { writeFileSync } from 'node:fs';
import { personalHomePage, projectLandingPage } from './pages.js';
const out = process.argv[2];
writeFileSync(out + '/pay.html', projectLandingPage());
writeFileSync(out + '/personal.html', personalHomePage());
console.log('rendered');
'@
Set-Content -LiteralPath $renderer -Value $rendererBody -Encoding ASCII

& node $renderer $tempDir
if ($LASTEXITCODE -ne 0) { throw 'node render failed' }

$payPath = Join-Path $outputDir 'pay.html'
$personalPath = Join-Path $outputDir 'personal.html'
Copy-Item -Force (Join-Path $tempDir 'pay.html') $payPath
Copy-Item -Force (Join-Path $tempDir 'personal.html') $personalPath
Remove-Item -Recurse -Force $tempDir

foreach ($file in @($payPath, $personalPath)) {
    $text = Get-Content -LiteralPath $file -Raw
    $apk = ([regex]::Matches($text, 'dl\.su46proj\.site|download\.su46proj\.site|\.apk')).Count
    $h5 = ([regex]::Matches($text, 'app\.su46proj\.site')).Count
    Write-Host ("  {0}: {1} bytes, apk refs={2}, h5 links={3}" -f (Split-Path $file -Leaf), $text.Length, $apk, $h5)
    if ($apk -gt 0) { throw ("{0} still references the retired APK" -f (Split-Path $file -Leaf)) }
}

if ($SkipApply) {
    Write-Host ''
    Write-Host '(-SkipApply) rendered files are in deploy/k3s/landing/; nothing was applied.' -ForegroundColor Yellow
    exit 0
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) { throw 'kubectl is required to apply' }

Write-Host '== update ConfigMaps =='
foreach ($pair in @(@{ cm = 'landing-pay'; file = $payPath }, @{ cm = 'landing-personal'; file = $personalPath })) {
    $manifest = & kubectl -n $Namespace create configmap $pair.cm --from-file=index.html=$($pair.file) --dry-run=client -o yaml
    if ($LASTEXITCODE -ne 0) { throw ("failed to render configmap {0}" -f $pair.cm) }
    $manifest | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) { throw ("failed to apply configmap {0}" -f $pair.cm) }
}

Write-Host '== restart landing Deployments =='
& kubectl -n $Namespace rollout restart deploy/landing-pay deploy/landing-personal | Out-Null
foreach ($deployment in @('landing-pay', 'landing-personal')) {
    & kubectl -n $Namespace rollout status "deploy/$deployment" --timeout="$($RolloutTimeoutSeconds)s"
    if ($LASTEXITCODE -ne 0) { throw ("rollout failed for {0}" -f $deployment) }
}

if ($NodeIp) {
    Write-Host '== verify the cluster origin (bypassing the edge cache) =='
    foreach ($host_ in @($LandingHost, $PersonalHost)) {
        $html = (& curl.exe -sk --max-time 20 --resolve "${host_}:443:$NodeIp" "https://$host_/") -join "`n"
        $apk = ([regex]::Matches($html, 'dl\.su46proj\.site|download\.su46proj\.site|\.apk')).Count
        Write-Host ("  {0}: apk refs={1}" -f $host_, $apk)
        if ($apk -gt 0) { throw ("origin for {0} still references the APK" -f $host_) }
    }
}

Write-Host ''
Write-Host 'Done. The public pages are still served from the edge cache; purge:' -ForegroundColor Yellow
Write-Host ("   https://{0}/   (Tencent CDN console: URL refresh)" -f $LandingHost) -ForegroundColor Yellow
Write-Host ("   https://{0}/        (Cloudflare: purge cache)" -f $PersonalHost) -ForegroundColor Yellow
exit 0
