# MiniPay consumer H5 -- live acceptance against the deployed environment.
#
# Runs entirely against public URLs (no ssh, no kubeconfig), so it can be executed
# from any machine that can reach the domain:
#
#   pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1
#   pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -ConfirmTransfer -PayPassword 123456
#   pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -SkipAi        # skip the model call
#
# What it checks (the parts a script CAN check; UI/merchant-QR flows still need a human):
#   1. H5 shell is served (index.html + bundle reference)
#   2. CSRF handshake
#   3. SMS login (demo code 123456, with 429 back-off)
#   4. wallet / bills / personal collection code
#   5. Miling chat streams token-by-token over SSE (real model call)
#   6. transfer prepare -> confirm (confirm is opt-in) -> balance bookkeeping
#   7. payment scan routing + input guards
#   8. landing page no longer advertises the retired Android APK (CDN freshness warning)
#
# Exit code 0 = every enabled check passed.
#
# NOTE: keep this file ASCII-only except the Chinese prompt sent to the model. Repo
# convention: PowerShell files carrying Chinese comments must be UTF-8 *with* BOM for
# Windows PowerShell 5.1; this one deliberately carries no Chinese comments.

param(
    [string] $BaseUrl = 'https://app.su46proj.site',
    [string] $LandingUrl = 'https://pay.su46proj.site/',
    [string] $LandingHost = 'pay.su46proj.site',
    # Optional: node public IP. When set, the landing check also probes the cluster origin
    # directly (curl --resolve) to tell "origin still wrong" apart from "CDN cache is stale".
    [string] $OriginIp = '',
    [string] $Mobile = '13900000009',
    [string] $PayeeIdentifier = 'minipay://friend/MP01A09916A8307D089F65',
    [int] $TransferFen = 1,
    [switch] $ConfirmTransfer,
    [string] $PayPassword = '',
    [switch] $SkipAi,
    [string] $AiPrompt = 'Use one short sentence to say what you can do',
    # Optional: a real merchant collection QR content (minipay://collect/merchant?token=...).
    # When supplied, the payment section also runs the full merchant flow
    # (scan -> prepare -> confirm) instead of only the routing/guard checks.
    # The payer must be an onboarded consumer that is NOT the merchant owner
    # (payment-service rejects SELF_MERCHANT_PAYMENT) and must have a payment password.
    [string] $MerchantQr = '',
    [int] $PaymentFen = 1,
    [int] $SmsRetries = 4,
    [int] $SmsBackoffSeconds = 25
)

$ErrorActionPreference = 'Stop'
# Merchant names / problem details are UTF-8; make the console print them readably.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$script:Passed = 0
$script:Failed = 0
$script:Warnings = New-Object System.Collections.Generic.List[string]

function Check([string] $Name, [bool] $Ok, [string] $Detail = '') {
    if ($Ok) {
        $script:Passed++
        Write-Host ("  [PASS] {0}{1}" -f $Name, $(if ($Detail) { " -- $Detail" } else { '' })) -ForegroundColor Green
    } else {
        $script:Failed++
        Write-Host ("  [FAIL] {0}{1}" -f $Name, $(if ($Detail) { " -- $Detail" } else { '' })) -ForegroundColor Red
    }
}

function Warn([string] $Message) {
    $script:Warnings.Add($Message)
    Write-Host ("  [WARN] {0}" -f $Message) -ForegroundColor Yellow
}

function Fail([string] $Message) {
    throw $Message
}

function Field($Json, [string] $Name) {
    if (-not $Json) { return $null }
    try { return ($Json | ConvertFrom-Json).$Name } catch { return $null }
}

# Windows PowerShell 5.1 + Cloudflare occasionally fails with
# "The underlying connection was closed: A connection that was expected to be kept alive was closed by the server."
# It is a transport-level race, not an application error, so retry a couple of times.
function Invoke-WebRequestRetry {
    param([hashtable] $Parameters, [int] $Attempts = 3, [int] $DelaySeconds = 2)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            return Invoke-WebRequest @Parameters
        } catch {
            $transport = -not $_.Exception.Response
            if (-not $transport -or $attempt -eq $Attempts) { throw }
            Write-Host ("  transport error, retry {0}/{1}: {2}" -f $attempt, $Attempts, $_.Exception.Message) -ForegroundColor DarkGray
            Start-Sleep -Seconds $DelaySeconds
        }
    }
}

function Invoke-Api {
    param(
        [string] $Method,
        [string] $Path,
        $Body,
        [string] $CsrfToken,
        [Microsoft.PowerShell.Commands.WebRequestSession] $Session,
        [switch] $NoThrow
    )
    $headers = @{}
    if ($CsrfToken) { $headers['X-CSRF-TOKEN'] = $CsrfToken }
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; WebSession = $Session; Headers = $headers; TimeoutSec = 40; UseBasicParsing = $true }
    if ($Body) {
        $parameters['Body'] = ($Body | ConvertTo-Json -Compress)
        $parameters['ContentType'] = 'application/json'
    }
    try {
        $response = Invoke-WebRequestRetry -Parameters $parameters
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $response.Content }
    } catch {
        $webResponse = $_.Exception.Response
        if (-not $webResponse -or -not $NoThrow) { throw }
        # PS 5.1: the error body has already been consumed into ErrorDetails.Message. Reading
        # the response stream directly yields nothing, which silently turned every 4xx
        # assertion into "code=<empty>".
        $content = $_.ErrorDetails.Message
        if (-not $content) {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
            $content = $reader.ReadToEnd()
        }
        return [pscustomobject]@{ Status = [int]$webResponse.StatusCode; Body = $content }
    }
}

Write-Host '== MiniPay H5 live acceptance =='
Write-Host ("   base    : {0}" -f $BaseUrl)
Write-Host ("   mobile  : {0}" -f $Mobile)
Write-Host ("   confirm : {0}" -f [bool]$ConfirmTransfer)
Write-Host ''

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Write-Host '[1/8] H5 shell'
$index = Invoke-WebRequestRetry -Parameters @{ Uri = "$BaseUrl/"; WebSession = $session; TimeoutSec = 30; UseBasicParsing = $true }
Check 'H5 index served' ($index.StatusCode -eq 200) ("HTTP {0}" -f $index.StatusCode)
Check 'H5 bundle referenced' ($index.Content -match 'umi\.[0-9a-f]+\.js') 'umi chunk present'
Check 'H5 title present' ($index.Content -match '<title>') 'index.html has a title'

Write-Host '[2/8] CSRF handshake'
$csrf = Invoke-Api -Method GET -Path '/api/v1/csrf' -Session $session
$csrfToken = Field $csrf.Body 'token'
Check 'CSRF token issued' ($csrf.Status -eq 200 -and [bool]$csrfToken) ("HTTP {0}, token len {1}" -f $csrf.Status, $csrfToken.Length)

Write-Host '[3/8] SMS login'
$challengeId = $null
for ($attempt = 1; $attempt -le $SmsRetries; $attempt++) {
    $sms = Invoke-Api -Method POST -Path '/api/v1/session/sms' -Body @{ mobile = $Mobile } -CsrfToken $csrfToken -Session $session -NoThrow
    $challengeId = Field $sms.Body 'challengeId'
    if ($challengeId) { break }
    if ($sms.Status -eq 429) {
        Write-Host ("  rate limited (429), retry {0}/{1} after {2}s" -f $attempt, $SmsRetries, $SmsBackoffSeconds) -ForegroundColor DarkGray
        Start-Sleep -Seconds $SmsBackoffSeconds
        continue
    }
    Fail ("SMS code request failed: HTTP {0} {1}" -f $sms.Status, $sms.Body)
}
Check 'SMS challenge issued' ([bool]$challengeId) ("challengeId {0}" -f $challengeId)

$login = Invoke-Api -Method POST -Path '/api/v1/session' -Body @{ mobile = $Mobile; challengeId = $challengeId; code = '123456' } -CsrfToken $csrfToken -Session $session
Check 'SMS login authenticated' ((Field $login.Body 'authenticated') -eq $true) ("HTTP {0}" -f $login.Status)
$onboardingRequired = Field $login.Body 'onboardingRequired'
$realNameStatus = Field $login.Body 'realNameStatus'
Check 'account ready for money movement' ($onboardingRequired -eq $false) ("onboardingRequired={0}, realNameStatus={1}" -f $onboardingRequired, $realNameStatus)

Write-Host '[4/8] wallet / bills / collection code'
$wallet = Invoke-Api -Method GET -Path '/api/v1/wallet' -Session $session
$balance = Field $wallet.Body 'availableAmountCent'
Check 'wallet balance read' ($wallet.Status -eq 200 -and $null -ne $balance) ("availableAmountCent={0}" -f $balance)
$bills = Invoke-Api -Method GET -Path '/api/v1/wallet/bills?cursor=1&limit=3' -Session $session
$billItems = @(Field $bills.Body 'items')
Check 'bill page read' ($bills.Status -eq 200) ("items={0}" -f $billItems.Count)
$code = Invoke-Api -Method GET -Path '/api/v1/collection-code' -Session $session
$deepLink = Field $code.Body 'deepLink'
Check 'personal collection code read' ($code.Status -eq 200 -and $deepLink -like 'minipay://collect/*') 'deepLink returned'

if ($SkipAi) {
    Write-Host '[5/8] Miling chat -- SKIPPED (-SkipAi)'
} else {
    Write-Host '[5/8] Miling chat (real model call, SSE)'
    $conversation = Invoke-Api -Method POST -Path '/api/v1/ai/conversations' -Body @{ title = 'acceptance' } -CsrfToken $csrfToken -Session $session
    $conversationId = Field $conversation.Body 'conversationId'
    Check 'conversation created' ([bool]$conversationId) ("conversationId={0}" -f $conversationId)

    $messagePath = '/api/v1/ai/conversations/' + $conversationId + '/messages'
    $messageBody = @{ content = $AiPrompt; clientMessageId = [guid]::NewGuid().ToString() }
    $run = Invoke-Api -Method POST -Path $messagePath -Body $messageBody -CsrfToken $csrfToken -Session $session
    $runId = Field $run.Body 'runId'
    Check 'run started' ([bool]$runId) ("runId={0}" -f $runId)

    $cookieHeader = (($session.Cookies.GetCookies([uri]$BaseUrl)) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '; '
    $sseFile = Join-Path ([System.IO.Path]::GetTempPath()) ("minipay-sse-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $sseUrl = "$BaseUrl/api/v1/ai/runs/$runId/events"
    & curl.exe -sN --max-time 90 -H "Cookie: $cookieHeader" $sseUrl | Set-Content -LiteralPath $sseFile -Encoding UTF8
    $sse = Get-Content -LiteralPath $sseFile -Raw
    Remove-Item -LiteralPath $sseFile -Force -ErrorAction SilentlyContinue
    if (-not $sse) { $sse = '' }

    $deltaFrames = ([regex]::Matches($sse, 'event:message\.delta')).Count
    $completed = $sse -match 'event:stream\.completed'
    $aiText = ''
    foreach ($line in ($sse -split "`n")) {
        if (-not $line.StartsWith('data:')) { continue }
        $payload = Field $line.Substring(5).Trim() 'payload'
        if ($payload -and $payload.text) { $aiText += $payload.text }
    }
    Check 'SSE streamed deltas' ($deltaFrames -ge 1) ("delta frames={0}" -f $deltaFrames)
    Check 'SSE stream completed' $completed 'stream.completed seen'
    Check 'model produced text' ([bool]$aiText) ("{0}..." -f $aiText.Substring(0, [Math]::Min(60, $aiText.Length)))

    $messagesPath = '/api/v1/ai/conversations/' + $conversationId + '/messages?limit=5'
    $messages = Invoke-Api -Method GET -Path $messagesPath -Session $session
    $items = @(Field $messages.Body 'items')
    $assistant = @($items | Where-Object { $_.role -eq 'assistant' })
    Check 'assistant message persisted' ($assistant.Count -ge 1) ("items={0}" -f $items.Count)
}

Write-Host '[6/8] transfer prepare/confirm'
$prepareBody = @{ payeeIdentifier = $PayeeIdentifier; amountFen = $TransferFen; remark = 'acceptance' }
$prepare = Invoke-Api -Method POST -Path '/api/v1/transfers/prepare' -Body $prepareBody -CsrfToken $csrfToken -Session $session -NoThrow
$intentId = Field $prepare.Body 'transferIntentId'
$prepareCode = Field $prepare.Body 'code'
Check 'transfer prepare' ([bool]$intentId -or $prepareCode -eq 'PAYMENT_PASSWORD_REQUIRED') ("HTTP {0}, code={1}" -f $prepare.Status, $prepareCode)

if ($intentId -and $ConfirmTransfer) {
    if (-not $PayPassword) { Fail '-ConfirmTransfer requires -PayPassword' }
    $confirmPath = '/api/v1/transfers/' + $intentId + '/confirm'
    $confirmBody = @{ amountFen = $TransferFen; paymentPassword = $PayPassword }
    $confirm = Invoke-Api -Method POST -Path $confirmPath -Body $confirmBody -CsrfToken $csrfToken -Session $session
    $status = Field $confirm.Body 'status'
    Check 'transfer confirmed' (@('SUCCESS', 'SUCCEEDED', 'PROCESSING') -contains $status) ("status={0}" -f $status)
    Start-Sleep -Seconds 3
    $walletAfter = Invoke-Api -Method GET -Path '/api/v1/wallet' -Session $session
    $balanceAfter = Field $walletAfter.Body 'availableAmountCent'
    Check 'balance decreased by the transfer' ($balanceAfter -eq ($balance - $TransferFen)) ("{0} -> {1} (fen)" -f $balance, $balanceAfter)
} elseif ($intentId) {
    $cancelPath = '/api/v1/transfers/' + $intentId
    $null = Invoke-Api -Method DELETE -Path $cancelPath -CsrfToken $csrfToken -Session $session -NoThrow
    Write-Host '  (intent cancelled; pass -ConfirmTransfer -PayPassword <pin> to exercise the debit)' -ForegroundColor DarkGray
}

Write-Host '[7/8] payment scan routing and guards'
$scan = Invoke-Api -Method POST -Path '/api/v1/payments/scan' -Body @{ deepLink = $deepLink } -CsrfToken $csrfToken -Session $session -NoThrow
$scanCode = Field $scan.Body 'code'
$scanType = Field $scan.Body 'type'
$scanAccepted = ($scan.Status -eq 200) -or (@('SELF_COLLECTION_CODE', 'COLLECTION_CODE_NOT_FOUND') -contains $scanCode)
Check 'payment scan reached the payment service' $scanAccepted ("HTTP {0}, code={1}, type={2}" -f $scan.Status, $scanCode, $scanType)
$fake = Invoke-Api -Method POST -Path '/api/v1/payments/scan' -Body @{ merchantToken = 'not-a-real-token' } -CsrfToken $csrfToken -Session $session -NoThrow
$fakeCode = Field $fake.Body 'code'
Check 'merchant token validated upstream' ($fakeCode -eq 'COLLECTION_CODE_NOT_FOUND') ("code={0}" -f $fakeCode)
$badPrepare = Invoke-Api -Method POST -Path '/api/v1/payments/prepare' -Body @{ resolutionId = ''; amountFen = 100 } -CsrfToken $csrfToken -Session $session -NoThrow
$badCode = Field $badPrepare.Body 'code'
Check 'payment prepare rejects a missing resolution' ($badPrepare.Status -eq 400) ("HTTP {0}, code={1}" -f $badPrepare.Status, $badCode)

if ($MerchantQr) {
    Write-Host '[7b/8] merchant scan-to-pay (real merchant QR)'
    if (-not $PayPassword) { Fail '-MerchantQr requires -PayPassword (the payer pin, e.g. 123456)' }
    # Re-read the balance: step 6 may have moved money, so the earlier snapshot is stale.
    $walletBeforePay = Invoke-Api -Method GET -Path '/api/v1/wallet' -Session $session
    $balanceBeforePay = Field $walletBeforePay.Body 'availableAmountCent'
    $scan = Invoke-Api -Method POST -Path '/api/v1/payments/scan' -Body @{ deepLink = $MerchantQr } -CsrfToken $csrfToken -Session $session -NoThrow
    $resolutionId = Field $scan.Body 'resolutionId'
    $merchantName = Field $scan.Body 'merchantName'
    Check 'merchant QR resolved' ([bool]$resolutionId) ("type={0}, merchant={1}" -f (Field $scan.Body 'type'), $merchantName)
    if ($resolutionId) {
        $payment = Invoke-Api -Method POST -Path '/api/v1/payments/prepare' `
            -Body @{ resolutionId = $resolutionId; amountFen = $PaymentFen } -CsrfToken $csrfToken -Session $session -NoThrow
        $paymentOrderId = Field $payment.Body 'paymentOrderId'
        $paymentOrderNo = Field $payment.Body 'paymentOrderNo'
        Check 'payment order created' ([bool]$paymentOrderId) ("{0} ({1} fen)" -f $paymentOrderNo, (Field $payment.Body 'amountFen'))
        if ($paymentOrderId) {
            $confirmPath = '/api/v1/payments/' + $paymentOrderId + '/confirm'
            $confirmBody = @{ amountFen = $PaymentFen; paymentPassword = $PayPassword }
            $confirmed = Invoke-Api -Method POST -Path $confirmPath -Body $confirmBody -CsrfToken $csrfToken -Session $session -NoThrow
            $payStatus = Field $confirmed.Body 'status'
            Check 'merchant payment confirmed' (@('SUCCESS', 'SUCCEEDED', 'PROCESSING') -contains $payStatus) ("status={0}, code={1}" -f $payStatus, (Field $confirmed.Body 'code'))
            Start-Sleep -Seconds 3
            $walletAfterPay = Invoke-Api -Method GET -Path '/api/v1/wallet' -Session $session
            $balanceAfterPay = Field $walletAfterPay.Body 'availableAmountCent'
            Check 'balance decreased by the payment' ($balanceAfterPay -eq ($balanceBeforePay - $PaymentFen)) ("{0} -> {1} (fen)" -f $balanceBeforePay, $balanceAfterPay)
        }
    }
}

Write-Host '[8/8] landing page no longer distributes the APK'
try {
    $landing = Invoke-WebRequestRetry -Parameters @{ Uri = $LandingUrl; TimeoutSec = 30; UseBasicParsing = $true }
    $apkRefs = ([regex]::Matches($landing.Content, 'dl\.su46proj\.site|download\.su46proj\.site|\.apk')).Count
    $h5Refs = ([regex]::Matches($landing.Content, 'app\.su46proj\.site')).Count
    if ($apkRefs -eq 0) {
        Check 'landing page has no APK entry' $true ("h5 links={0}" -f $h5Refs)
    } else {
        # Distinguish "the cluster origin is still wrong" from "only the CDN cache is stale":
        # pay.su46proj.site sits behind a Tencent CDN we cannot purge without credentials.
        $originDetail = ''
        if ($OriginIp) {
            $originHtml = & curl.exe -sk --max-time 20 --resolve "${LandingHost}:443:$OriginIp" $LandingUrl
            $originApkRefs = ([regex]::Matches(($originHtml -join ''), 'dl\.su46proj\.site|download\.su46proj\.site|\.apk')).Count
            $originDetail = if ($originApkRefs -eq 0) { ' (origin is clean: purge the CDN cache)' } else { ' (origin ALSO still has it: re-check the ConfigMap)' }
        }
        Check 'landing page has no APK entry' $false ("apk refs={0}{1} -- purge {2} in the CDN console" -f $apkRefs, $originDetail, $LandingUrl)
    }
} catch {
    Warn ("landing page not reachable: {0}" -f $_.Exception.Message)
}

Write-Host ''
Write-Host ("== summary: {0} passed, {1} failed, {2} warnings ==" -f $script:Passed, $script:Failed, $script:Warnings.Count)
foreach ($warning in $script:Warnings) { Write-Host ("   - {0}" -f $warning) -ForegroundColor Yellow }
if ($script:Failed -gt 0) { exit 1 }
exit 0
