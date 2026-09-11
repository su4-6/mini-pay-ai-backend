param(
    [string]$PhoneA = "13900001001",
    [string]$PhoneB = "13900001002",
    [string]$PayPassword = "258369",
    [string]$SmsCode = "123456",
    [string]$IdentityBaseUrl = "http://localhost:8081",
    [string]$PaymentBaseUrl = "http://localhost:8082",
    [string]$WalletBaseUrl = "http://localhost:8083",
    [string]$CommerceBaseUrl = "http://localhost:8085",
    [string]$AgentBaseUrl = "http://localhost:8086",
    [string]$YShopBaseUrl = "http://localhost:48080"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Local *.localhost routes must never be sent through the workstation HTTP
# proxy; doing so produces misleading intermittent 502 responses.
if (@($IdentityBaseUrl, $PaymentBaseUrl, $WalletBaseUrl, $CommerceBaseUrl, $AgentBaseUrl, $YShopBaseUrl) |
        Where-Object { $_ -match '\.localhost(?::\d+)?(?:/|$)' }) {
    [System.Net.WebRequest]::DefaultWebProxy = [System.Net.WebProxy]::new()
    if ((Get-Command Invoke-RestMethod).Parameters.ContainsKey('NoProxy')) {
        $PSDefaultParameterValues['Invoke-RestMethod:NoProxy'] = $true
    }
}

function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-PkcePair {
    $random = New-Object byte[] 64
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $verifier = ConvertTo-Base64Url $random
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
    } finally {
        $sha.Dispose()
    }
    return [pscustomobject]@{ Verifier = $verifier; Challenge = $challenge }
}

function New-IdempotencyKey([string]$Prefix) {
    return "$Prefix-$([Guid]::NewGuid().ToString('N'))"
}

function Resolve-LocalGatewayUri([string]$Uri) {
    $parsed = [Uri]$Uri
    if ($parsed.Host -notmatch '\.minipay\.localhost$') {
        return [pscustomobject]@{ Uri = $Uri; Host = $null }
    }
    $builder = [UriBuilder]$parsed
    $builder.Host = '127.0.0.1'
    return [pscustomobject]@{ Uri = $builder.Uri.AbsoluteUri; Host = $parsed.Host }
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$AccessToken,
        $Body = $null,
        [string]$IdempotencyKey = "",
        [hashtable]$AdditionalHeaders = @{}
    )
    $headers = @{}
    if ($AccessToken) { $headers.Authorization = "Bearer $AccessToken" }
    if ($IdempotencyKey) { $headers["Idempotency-Key"] = $IdempotencyKey }
    foreach ($name in $AdditionalHeaders.Keys) { $headers[$name] = $AdditionalHeaders[$name] }
    $resolved = Resolve-LocalGatewayUri $Uri
    if ($resolved.Host) { $headers.Host = $resolved.Host }
    $request = @{
        Method = $Method
        Uri = $resolved.Uri
        Headers = $headers
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    try {
        return Invoke-RestMethod @request
    } catch {
        $details = ""
        if ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) {
            try {
                $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
                try { $details = $reader.ReadToEnd() } finally { $reader.Dispose() }
            } catch { }
        }
        throw "API request failed: $Method $Uri. $($_.Exception.Message) $details"
    }
}

function Exchange-AuthorizationCode($AuthorizationCode, $Verifier) {
    $resolved = Resolve-LocalGatewayUri "$IdentityBaseUrl/oauth2/token"
    $headers = @{}
    if ($resolved.Host) { $headers.Host = $resolved.Host }
    return Invoke-RestMethod -UseBasicParsing -Method Post `
        -Uri $resolved.Uri -Headers $headers `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "authorization_code"
            client_id = "minipay-android"
            code = $AuthorizationCode
            redirect_uri = "com.minipay.mobile:/oauth2redirect"
            code_verifier = $Verifier
        }
}

function Refresh-ConsumerToken([string]$RefreshToken) {
    $resolved = Resolve-LocalGatewayUri "$IdentityBaseUrl/oauth2/token"
    $headers = @{}
    if ($resolved.Host) { $headers.Host = $resolved.Host }
    return Invoke-RestMethod -UseBasicParsing -Method Post `
        -Uri $resolved.Uri -Headers $headers `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "refresh_token"
            client_id = "minipay-android"
            refresh_token = $RefreshToken
        }
}

function Read-JwtPayload([string]$AccessToken) {
    $payload = $AccessToken.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        2 { $payload += "==" }
        3 { $payload += "=" }
    }
    return ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json)
}

function Invoke-SandboxRealNameVerification([string]$AccessToken) {
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $client.DefaultRequestHeaders.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $AccessToken)
        $client.DefaultRequestHeaders.Add("Idempotency-Key", (New-IdempotencyKey "real-name"))
        $multipart.Add([System.Net.Http.StringContent]::new("测试用户"), "legalName")
        $multipart.Add([System.Net.Http.StringContent]::new("11010519491231002X"), "idNumber")
        $face = [System.Net.Http.ByteArrayContent]::new([byte[]](0xff, 0xd8, 0xff, 0xd9))
        $face.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("image/jpeg")
        $multipart.Add($face, "faceImage", "face.jpg")
        $response = $client.PostAsync(
            "$IdentityBaseUrl/api/v1/real-name-verifications", $multipart).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Sandbox real-name verification failed: HTTP $([int]$response.StatusCode) $body"
        }
        return $body | ConvertFrom-Json
    } finally {
        $multipart.Dispose()
        $client.Dispose()
    }
}

function New-ConsumerSession([string]$Phone, [string]$Nickname) {
    Write-Host "Creating consumer session for $Phone"
    $deviceId = "smoke-$Phone"
    $pkce = New-PkcePair
    $challenge = Invoke-JsonApi Post "$IdentityBaseUrl/api/v1/auth/consumer/code/send" "" @{
        mobile = $Phone
        purpose = "LOGIN"
    }
    $verified = Invoke-JsonApi Post "$IdentityBaseUrl/api/v1/auth/consumer/code/verify" "" @{
        challengeId = $challenge.challengeId
        code = $SmsCode
        clientId = "minipay-android"
        redirectUri = "com.minipay.mobile:/oauth2redirect"
        codeChallenge = $pkce.Challenge
        codeChallengeMethod = "S256"
        deviceId = $deviceId
    }
    $tokens = Exchange-AuthorizationCode $verified.authorizationCode $pkce.Verifier
    $profileChanged = $false
    if ($verified.onboardingRequired) {
        Invoke-JsonApi Put "$IdentityBaseUrl/api/v1/users/me/onboarding" $tokens.access_token @{
            nickname = $Nickname
            avatarUploadId = $null
        } (New-IdempotencyKey "onboarding") | Out-Null
        $profileChanged = $true
    }
    if (-not $verified.payPasswordSet) {
        Invoke-JsonApi Put "$IdentityBaseUrl/api/v1/users/me/payment-password" $tokens.access_token @{
            paymentPassword = $PayPassword
        } | Out-Null
        $profileChanged = $true
    }
    if (-not $verified.realNameVerified) {
        $realName = Invoke-SandboxRealNameVerification $tokens.access_token
        if ($realName.status -ne "VERIFIED") {
            throw "Sandbox real-name verification did not reach VERIFIED"
        }
        $profileChanged = $true
    }
    if ($profileChanged) {
        $tokens = Refresh-ConsumerToken $tokens.refresh_token
    }
    $claims = Read-JwtPayload $tokens.access_token
    $audiences = @($claims.aud) -join ","
    $scopes = if ($claims.scope -is [array]) { $claims.scope -join " " } else { [string]$claims.scope }
    Write-Host "Consumer token claims: aud=$audiences; scopes=$scopes"
    if (-not $claims.onboarding_completed -or -not $claims.pay_password_set -or
            -not $claims.real_name_verified) {
        throw "Consumer token does not contain ready onboarding, payment-password and real-name claims"
    }
    return [pscustomobject]@{
        UserId = [string]$claims.user_id
        AccessToken = [string]$tokens.access_token
        RefreshToken = [string]$tokens.refresh_token
        DeviceId = $deviceId
    }
}

function Wait-ForWallet($Session) {
    $lastError = $null
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            $wallet = Invoke-JsonApi Get "$WalletBaseUrl/api/v1/wallets/me" $Session.AccessToken
            return [pscustomobject]@{ BaseUrl = $WalletBaseUrl; Wallet = $wallet }
        } catch {
            $lastError = $_
        }
        Start-Sleep -Seconds 1
    }
    throw "Wallet did not become ready within 30 seconds. Last error: $lastError"
}

function New-PaymentAuthorization($Session, [string]$SubjectType, [string]$SubjectId, [long]$AmountCent) {
    return Invoke-JsonApi Post "$IdentityBaseUrl/api/v1/payment-authorizations" $Session.AccessToken @{
        subjectType = $SubjectType
        subjectId = $SubjectId
        amountCent = $AmountCent
        deviceId = $Session.DeviceId
        payPassword = $PayPassword
    } (New-IdempotencyKey "payment-auth")
}

$consumerA = New-ConsumerSession $PhoneA "TesterA"
$consumerB = New-ConsumerSession $PhoneB "TesterB"
$walletA = Wait-ForWallet $consumerA
$walletB = Wait-ForWallet $consumerB
Write-Host "Payer wallet ready on $($walletA.BaseUrl), balance=$($walletA.Wallet.availableAmountCent)"
Write-Host "Receiver wallet ready on $($walletB.BaseUrl), balance=$($walletB.Wallet.availableAmountCent)"

$card = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/bank-cards" $consumerA.AccessToken @{
    holderName = "TEST USER"
    cardNumber = "4111111111111111"
    verificationCode = "123456"
}
Write-Host "Bank card bound: $($card.cardId)"

$recharge = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/recharge-orders" $consumerA.AccessToken @{
    bankCardId = $card.cardId
    amountCent = 10000
} (New-IdempotencyKey "recharge")
Write-Host "Recharge: $($recharge.status), amount=$($recharge.amountCent)"

$alipay = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders" $consumerA.AccessToken @{
    amountCent = 101
    subject = "Sandbox Alipay order"
    paymentMethod = "ALIPAY"
} (New-IdempotencyKey "alipay")
Write-Host "Alipay sandbox order created: $($alipay.status)"

$wechat = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders" $consumerA.AccessToken @{
    amountCent = 102
    subject = "Sandbox WeChat Pay order"
    paymentMethod = "WECHAT_PAY"
} (New-IdempotencyKey "wechat")
Write-Host "WeChat Pay sandbox order created: $($wechat.status)"

$walletPayment = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders" $consumerA.AccessToken @{
    amountCent = 103
    subject = "Wallet balance order"
    paymentMethod = "WALLET_BALANCE"
} (New-IdempotencyKey "wallet-payment")
$walletPaymentAuth = New-PaymentAuthorization $consumerA "PAYMENT_ORDER" $walletPayment.paymentOrderId 103
$walletPayment = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders/$($walletPayment.paymentOrderId)/confirm" $consumerA.AccessToken @{
    paymentAuthToken = $walletPaymentAuth.paymentAuthToken
}
Write-Host "Wallet balance payment: $($walletPayment.status)"

$withdrawal = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/withdrawal-orders" $consumerA.AccessToken @{
    bankCardId = $card.cardId
    amountCent = 200
} (New-IdempotencyKey "withdrawal")
$withdrawalAuth = New-PaymentAuthorization $consumerA "WITHDRAWAL_ORDER" $withdrawal.withdrawalId 200
$withdrawal = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/withdrawal-orders/$($withdrawal.withdrawalId)/confirm" $consumerA.AccessToken @{
    paymentAuthToken = $withdrawalAuth.paymentAuthToken
}
Write-Host "Withdrawal: $($withdrawal.status)"

$collectionCode = Invoke-JsonApi Get "$PaymentBaseUrl/api/v1/personal-collection-codes/current" $consumerB.AccessToken
$resolution = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/scan-resolutions" $consumerA.AccessToken @{
    deepLink = $collectionCode.deepLink
}
if ([string]$resolution.receiverUserId -ne $consumerB.UserId) {
    throw "Collection code resolved to an unexpected receiver"
}
Write-Host "Collection code resolved to user B"

$intent = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/transfers" $consumerA.AccessToken @{
    receiverUserId = $consumerB.UserId
    amountCent = 300
    remark = "smoke transfer"
    source = "PERSONAL_COLLECTION_CODE"
} (New-IdempotencyKey "transfer-intent")
$transferAuth = New-PaymentAuthorization $consumerA "TRANSFER_INTENT" $intent.intentId 300
$transfer = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/transfers/$($intent.intentId)/confirm" $consumerA.AccessToken @{
    paymentAuthToken = $transferAuth.paymentAuthToken
} (New-IdempotencyKey "transfer-confirm")
Write-Host "Transfer: $($transfer.status)"

$walletAAfter = Invoke-JsonApi Get "$($walletA.BaseUrl)/api/v1/wallets/me" $consumerA.AccessToken
$walletBAfter = Invoke-JsonApi Get "$($walletB.BaseUrl)/api/v1/wallets/me" $consumerB.AccessToken
$billsA = Invoke-JsonApi Get "$($walletA.BaseUrl)/api/v1/wallets/me/bills?page=1&size=20" $consumerA.AccessToken
$billsB = Invoke-JsonApi Get "$($walletB.BaseUrl)/api/v1/wallets/me/bills?page=1&size=20" $consumerB.AccessToken

Start-Sleep -Seconds 2
$foodPhoneChallenge = Invoke-JsonApi Post `
    "$IdentityBaseUrl/api/v1/users/me/application-authorizations/yshop-food/phone-challenges" `
    $consumerA.AccessToken @{ mobile = $PhoneA }
$foodAuthorization = Invoke-JsonApi Post `
    "$IdentityBaseUrl/api/v1/users/me/application-authorizations/yshop-food" `
    $consumerA.AccessToken @{
        scopes = @("profile.basic", "profile.phone", "location.current")
        consentVersion = 2
        phoneChallengeId = $foodPhoneChallenge.challengeId
        verificationCode = $SmsCode
    } (New-IdempotencyKey "food-auth")
if ($foodAuthorization.state -ne "ACTIVE") { throw "YShop authorization is not ACTIVE" }

$foodBinding = Invoke-JsonApi Post "$CommerceBaseUrl/api/v1/commerce/food-bindings" `
    $consumerA.AccessToken
if (-not $foodBinding.active) { throw "Commerce/YShop binding is not active" }

$handoff = Invoke-JsonApi -Method Post -Uri "$CommerceBaseUrl/api/v1/commerce/food-handoffs" `
    -AccessToken $consumerA.AccessToken -AdditionalHeaders @{ "X-Device-Id" = $consumerA.DeviceId }
$yshopLogin = Invoke-JsonApi -Method Post -Uri "$YShopBaseUrl/app-api/minipay/auth/handoff" `
    -AccessToken "" -Body @{ code = $handoff.code; deviceProof = $handoff.deviceProof } `
    -AdditionalHeaders @{ Origin = $handoff.origin }
if (-not $yshopLogin.data.accessToken) { throw "YShop handoff did not return an access token" }

$replayRejected = $false
try {
    Invoke-JsonApi -Method Post -Uri "$YShopBaseUrl/app-api/minipay/auth/handoff" `
        -AccessToken "" -Body @{ code = $handoff.code; deviceProof = $handoff.deviceProof } `
        -AdditionalHeaders @{ Origin = $handoff.origin } | Out-Null
} catch {
    $replayRejected = $true
}
if (-not $replayRejected) { throw "YShop handoff replay was unexpectedly accepted" }

$location = Invoke-JsonApi Post "$CommerceBaseUrl/api/v1/commerce/food-location-contexts" `
    $consumerA.AccessToken @{
        # Match the retained YShop seed store instead of probing Hangzhou,
        # which made a healthy integration look like an empty result.
        longitude = 114.04877877439665
        latitude = 33.57093831826829
        accuracyMeters = 20
        capturedAt = [DateTime]::UtcNow.ToString("o")
        source = "GPS"
    }
$nearby = Invoke-JsonApi Post "$YShopBaseUrl/app-api/minipay/food/stores/nearby" `
    $yshopLogin.data.accessToken @{
        locationContextId = [string]$location.locationContextId
        addressId = $null
        fulfillmentType = "TAKEOUT"
    }
if ($nearby.code -ne 0) { throw "YShop nearby-store request failed with code $($nearby.code)" }
if (@($nearby.data).Count -lt 1) { throw "YShop nearby-store request returned no store" }

# Exercise the same quote/order endpoints used by Food H5. The retained YShop
# seed data exposes shop 2 / SKU 146 as an in-stock pickup item.
$foodQuote = Invoke-JsonApi Post "$YShopBaseUrl/app-api/minipay/food/checkout-quotes" `
    $yshopLogin.data.accessToken @{
        shopId = 2
        addressId = $null
        fulfillmentType = "PICKUP"
        items = @(@{ skuId = 146; quantity = 1 })
    }
if ($foodQuote.code -ne 0 -or -not $foodQuote.data.quoteId) {
    throw "YShop checkout quote failed"
}
$foodOrderKey = New-IdempotencyKey "food-order"
$foodOrder = Invoke-JsonApi Post "$YShopBaseUrl/app-api/minipay/food/orders" `
    $yshopLogin.data.accessToken @{
        quoteId = $foodQuote.data.quoteId
        remark = "local acceptance"
    } $foodOrderKey
if ($foodOrder.code -ne 0 -or -not $foodOrder.data.orderRefId) {
    throw "YShop food order creation failed: code=$($foodOrder.code), message=$($foodOrder.msg)"
}
$foodOrderReplay = Invoke-JsonApi Post "$YShopBaseUrl/app-api/minipay/food/orders" `
    $yshopLogin.data.accessToken @{
        quoteId = $foodQuote.data.quoteId
        remark = "local acceptance"
    } $foodOrderKey
if ($foodOrderReplay.data.orderRefId -ne $foodOrder.data.orderRefId) {
    throw "YShop food order idempotency replay returned a different order"
}

$agentConversation = Invoke-JsonApi Post "$AgentBaseUrl/api/v1/agent/ai/conversations" `
    $consumerA.AccessToken @{ title = "local acceptance" }
if (-not $agentConversation.id) { throw "Agent conversation creation failed" }
$agentRun = Invoke-JsonApi Post `
    "$AgentBaseUrl/api/v1/agent/ai/conversations/$($agentConversation.id)/runs" `
    $consumerA.AccessToken @{
        clientMessageId = [Guid]::NewGuid()
        message = "查询我的钱包余额"
        contextVersion = 0
        locationContextId = $null
    } (New-IdempotencyKey "agent-run")
if (-not $agentRun.runId -or -not $agentRun.eventsUrl) { throw "Agent run creation failed" }
$agentRunView = Invoke-JsonApi Get "$AgentBaseUrl/api/v1/agent/ai/runs/$($agentRun.runId)" `
    $consumerA.AccessToken
if ($agentRunView.runId -ne $agentRun.runId) { throw "Agent run lookup failed" }

[pscustomobject]@{
    UserA = $consumerA.UserId
    UserB = $consumerB.UserId
    WalletABalance = $walletAAfter.availableAmountCent
    WalletBBalance = $walletBAfter.availableAmountCent
    RechargeStatus = $recharge.status
    AlipayStatus = $alipay.status
    WeChatStatus = $wechat.status
    WalletPaymentStatus = $walletPayment.status
    WithdrawalStatus = $withdrawal.status
    TransferStatus = $transfer.status
    UserABillCount = $billsA.total
    UserBBillCount = $billsB.total
    YShopAuthorization = $foodAuthorization.state
    YShopBinding = $foodBinding.active
    YShopHandoff = "ACCEPTED_ONCE_REPLAY_REJECTED"
    NearbyStoreCount = @($nearby.data).Count
    FoodQuote = "CREATED"
    FoodOrder = "CREATED_IDEMPOTENTLY"
    AgentRun = $agentRunView.status
    AgentSseUrl = "PUBLISHED"
} | Format-List
