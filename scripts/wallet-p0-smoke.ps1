param(
    [string]$PhoneA = "13900001001",
    [string]$PhoneB = "13900001002",
    [string]$PayPassword = "258369",
    [string]$SmsCode = "123456",
    [string]$IdentityBaseUrl = "http://localhost:8081",
    [string]$PaymentBaseUrl = "http://localhost:8082",
    [string]$WalletBaseUrl = "http://localhost:8083"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$AccessToken,
        $Body = $null,
        [string]$IdempotencyKey = ""
    )
    $headers = @{}
    if ($AccessToken) { $headers.Authorization = "Bearer $AccessToken" }
    if ($IdempotencyKey) { $headers["Idempotency-Key"] = $IdempotencyKey }
    $request = @{
        Method = $Method
        Uri = $Uri
        Headers = $headers
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    return Invoke-RestMethod @request
}

function Exchange-AuthorizationCode($AuthorizationCode, $Verifier) {
    return Invoke-RestMethod -UseBasicParsing -Method Post `
        -Uri "$IdentityBaseUrl/oauth2/token" `
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
    return Invoke-RestMethod -UseBasicParsing -Method Post `
        -Uri "$IdentityBaseUrl/oauth2/token" `
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

function New-ConsumerSession([string]$Phone, [string]$Nickname) {
    Write-Host "Creating consumer session for $Phone"
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
        deviceId = "smoke-$Phone"
    }
    $tokens = Exchange-AuthorizationCode $verified.authorizationCode $pkce.Verifier
    if ($verified.onboardingRequired) {
        Invoke-JsonApi Put "$IdentityBaseUrl/api/v1/users/me/onboarding" $tokens.access_token @{
            nickname = $Nickname
            avatarObjectKey = $null
            payPassword = $PayPassword
        } (New-IdempotencyKey "onboarding") | Out-Null
        $tokens = Refresh-ConsumerToken $tokens.refresh_token
    }
    $claims = Read-JwtPayload $tokens.access_token
    if (-not $claims.onboarding_completed -or -not $claims.pay_password_set) {
        throw "Consumer token does not contain ready onboarding claims"
    }
    return [pscustomobject]@{
        UserId = [string]$claims.user_id
        AccessToken = [string]$tokens.access_token
        RefreshToken = [string]$tokens.refresh_token
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
        deviceId = "smoke-device"
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
$alipay = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders/$($alipay.paymentOrderId)/sandbox-result" $consumerA.AccessToken @{
    succeeded = $true
}
Write-Host "Alipay sandbox payment: $($alipay.status)"

$wechat = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders" $consumerA.AccessToken @{
    amountCent = 102
    subject = "Sandbox WeChat Pay order"
    paymentMethod = "WECHAT_PAY"
} (New-IdempotencyKey "wechat")
$wechat = Invoke-JsonApi Post "$PaymentBaseUrl/api/v1/payment-orders/$($wechat.paymentOrderId)/sandbox-result" $consumerA.AccessToken @{
    succeeded = $true
}
Write-Host "WeChat Pay sandbox payment: $($wechat.status)"

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
    source = "QR"
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
} | Format-List
