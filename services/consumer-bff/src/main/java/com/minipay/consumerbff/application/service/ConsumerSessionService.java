package com.minipay.consumerbff.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.consumerbff.application.error.SessionRequiredException;
import com.minipay.consumerbff.application.error.UpstreamProblemException;
import com.minipay.consumerbff.application.port.ConsumerApiGateway;
import com.minipay.consumerbff.application.port.ConsumerProfileGateway;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway.IssuedAuthorizationCode;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway.OAuthTokenSet;
import com.minipay.consumerbff.application.port.IdentityConsumerGateway;
import com.minipay.consumerbff.application.port.SessionTokenStore;
import com.minipay.consumerbff.application.port.UpstreamResponse;
import com.minipay.consumerbff.domain.identity.MobileMasking;
import com.minipay.consumerbff.domain.identity.SmsChallenge;
import com.minipay.consumerbff.domain.security.Pkce;
import com.minipay.consumerbff.domain.session.ConsumerSession;
import com.minipay.consumerbff.domain.session.ConsumerTokens;
import com.minipay.consumerbff.domain.session.LoginChallenge;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Consumer H5 use cases: browser session lifecycle plus the token-relay proxy.
 *
 * <p>Access and refresh tokens only ever live in the server-side WebSession. Nothing in this class
 * returns, logs or stores a payment password: it is passed straight to Identity for a single-use
 * authorization token and then dropped.
 */
public class ConsumerSessionService {

    private static final ObjectMapper DOCUMENT_READER = new ObjectMapper();

    /**
     * Fixed subject for a scanned merchant payment. It reaches the merchant and operations consoles
     * as a bill line, so it deliberately carries no browser-supplied text.
     */
    private static final String PAYMENT_SUBJECT = "扫码付款";

    private final IdentityAuthorizationGateway identity;
    private final IdentityConsumerGateway identityConsumer;
    private final ConsumerProfileGateway profiles;
    private final ConsumerApiGateway upstream;
    private final String clientId;
    private final String redirectUri;

    public ConsumerSessionService(
            IdentityAuthorizationGateway identity,
            IdentityConsumerGateway identityConsumer,
            ConsumerProfileGateway profiles,
            ConsumerApiGateway upstream,
            String clientId,
            String redirectUri) {
        this.identity = identity;
        this.identityConsumer = identityConsumer;
        this.profiles = profiles;
        this.upstream = upstream;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    /**
     * WebFlux has no request scope, so every use case receives the {@code WebSession} explicitly and
     * adapts it to the session port for the duration of the call.
     */
    private static SessionTokenStore session(WebSession webSession) {
        return new com.minipay.consumerbff.infrastructure.session.WebSessionTokenStore(
                Mono.just(webSession));
    }

    // ------------------------------------------------------------------ login

    /** Step 1: send the SMS code and remember the server-side PKCE verifier and device binding. */
    public Mono<SmsChallenge> requestSmsCode(WebSession webSession, String mobile, String requestId) {
        SessionTokenStore sessions = session(webSession);
        Pkce pkce = Pkce.generate();
        String deviceId = UUID.randomUUID().toString();
        return identity.sendSmsCode(mobile, requestId)
                .flatMap(challenge -> sessions.storeLoginChallenge(
                                new LoginChallenge(pkce, deviceId, challenge.challengeId()))
                        .thenReturn(challenge));
    }

    /** Step 2: verify the code, exchange it for tokens and rotate the session identifier. */
    public Mono<ConsumerSession> completeLogin(
            WebSession webSession, String mobile, String challengeId, String code, String requestId) {
        SessionTokenStore sessions = session(webSession);
        if (mobile == null || !mobile.matches("^1[3-9]\\d{9}$")) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "MOBILE_INVALID", "手机号格式不正确"));
        }
        return sessions.loadLoginChallenge()
                .switchIfEmpty(Mono.error(new UpstreamProblemException(
                        HttpStatus.BAD_REQUEST,
                        "LOGIN_CHALLENGE_NOT_FOUND",
                        "请重新获取短信验证码")))
                .flatMap(challenge -> {
                    if (!challenge.challengeId().equals(challengeId)) {
                        return Mono.error(new UpstreamProblemException(
                                HttpStatus.BAD_REQUEST,
                                "LOGIN_CHALLENGE_MISMATCH",
                                "验证码会话已失效，请重新获取"));
                    }
                    return identity.verifySmsCode(
                                    challengeId,
                                    code,
                                    clientId,
                                    redirectUri,
                                    challenge.pkce().challenge(),
                                    "S256",
                                    challenge.deviceId(),
                                    requestId)
                            .flatMap(issued -> identity.exchangeCode(
                                            issued.authorizationCode(),
                                            challenge.pkce().verifier(),
                                            requestId)
                                    .flatMap(tokens -> establishSession(
                                            sessions, issued, tokens, requestId)));
                });
    }

    private Mono<ConsumerSession> establishSession(
            SessionTokenStore sessions,
            IssuedAuthorizationCode issued,
            OAuthTokenSet tokens,
            String requestId) {
        String maskedPhone = MobileMasking.mask(issued.rawMobile());
        return profiles.load(
                        tokens,
                        issued.userId(),
                        maskedPhone,
                        issued.payPasswordSet(),
                        issued.onboardingRequired(),
                        issued.realNameStatus(),
                        issued.realNameVerified(),
                        requestId)
                .flatMap(identityView -> {
                    ConsumerSession value = new ConsumerSession(
                            UUID.fromString(identityView.userId()),
                            identityView.maskedPhone(),
                            identityView.displayName(),
                            identityView.payPasswordSet(),
                            identityView.onboardingRequired(),
                            identityView.realNameStatus(),
                            identityView.realNameVerified());
                    // Rotate before writing the authenticated state: the pre-authentication
                    // identifier must never survive into the authenticated session, and the state
                    // is written afterwards so nothing depends on attributes surviving rotation.
                    return sessions.rotateSessionId()
                            .then(sessions.storeTokens(new ConsumerTokens(
                                    tokens.accessToken(), tokens.refreshToken())))
                            .then(sessions.storeConsumerSession(value))
                            .then(sessions.clearLoginChallenge())
                            .thenReturn(value);
                });
    }

    public Mono<ConsumerSession> currentSession(WebSession webSession) {
        return session(webSession).loadConsumerSession();
    }

    public Mono<Void> logout(WebSession webSession, String requestId) {
        SessionTokenStore sessions = session(webSession);
        return sessions.loadTokens()
                .flatMap(tokens -> tokens.hasRefreshToken()
                        // Best effort: a failed revocation must not block the local logout.
                        ? identity.revoke(tokens.refreshToken(), requestId)
                                .onErrorResume(ignored -> Mono.empty())
                        : Mono.empty())
                .then(sessions.invalidate());
    }

    // ----------------------------------------------------------- pay password

    public Mono<Void> setPaymentPassword(
            WebSession webSession, String paymentPassword, String requestId) {
        SessionTokenStore sessions = session(webSession);
        return sessions.loadTokens()
                .flatMap(tokens -> identityConsumer.setInitialPaymentPassword(
                        webSession, tokens.accessToken(), paymentPassword, requestId))
                // 设置成功后必须换一次访问令牌：pay_password_set 是 identity 在签发时从库里读出来写进
                // JWT 的 claim，旧令牌仍然带着 false，随后转账/付款会被 identity 判成
                // PAYMENT_PASSWORD_REQUIRED（实测：重登一次就好）。刷新失败不能影响"密码已设置"这个
                // 事实，所以这里 best-effort。
                .then(refreshOnce(sessions, requestId)
                        .onErrorResume(ignored -> Mono.empty())
                        .then())
                // 同时刷新会话快照：否则 /api/v1/session 一直回 payPasswordSet=false，
                // 前端会继续显示「尚未设置支付密码」并禁用转账/付款按钮。
                .then(sessions.loadConsumerSession()
                        .flatMap(current -> sessions.storeConsumerSession(new ConsumerSession(
                                current.consumerId(),
                                current.maskedPhone(),
                                current.displayName(),
                                true,
                                current.onboardingRequired(),
                                current.realNameStatus(),
                                current.realNameVerified())))
                        .then());
    }

    // ------------------------------------------------------------------ proxy

    public Mono<UpstreamResponse> proxy(
            WebSession session,
            ServerWebExchange exchange,
            HttpMethod method,
            String targetPath,
            Map<String, String> query,
            Object body,
            String idempotencyKey) {
        return upstream.call(
                session, exchange.getRequest(), method, targetPath, query, body, idempotencyKey);
    }

    public Mono<UpstreamResponse> relay(WebSession session, ServerWebExchange exchange) {
        return upstream.relay(
                session,
                exchange.getRequest(),
                HttpMethod.valueOf(exchange.getRequest().getMethod().name()),
                null);
    }

    public Flux<ServerSentEvent<String>> streamEvents(
            WebSession session, ServerWebExchange exchange, String targetPath) {
        return upstream.events(session, exchange.getRequest(), targetPath, null);
    }

    // ------------------------------------------------------ consumer profile

    public Mono<UpstreamResponse> updateProfile(
            WebSession webSession,
            ServerWebExchange exchange,
            String nickname,
            long version) {
        return upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.PATCH,
                        "/api/v1/users/me",
                        Map.of(),
                        Map.of("nickname", nickname, "version", version),
                        null)
                .flatMap(response -> updateSessionWhenSuccessful(
                        webSession,
                        response,
                        current -> new ConsumerSession(
                                current.consumerId(),
                                current.maskedPhone(),
                                nickname,
                                current.payPasswordSet(),
                                current.onboardingRequired(),
                                current.realNameStatus(),
                                current.realNameVerified())));
    }

    public Mono<UpstreamResponse> completeOnboarding(
            WebSession webSession,
            ServerWebExchange exchange,
            String nickname,
            String idempotencyKey) {
        return upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.PUT,
                        "/api/v1/users/me/onboarding",
                        Map.of(),
                        Map.of("nickname", nickname),
                        idempotencyKey)
                .flatMap(response -> updateSessionWhenSuccessful(
                        webSession,
                        response,
                        current -> new ConsumerSession(
                                current.consumerId(),
                                current.maskedPhone(),
                                nickname,
                                current.payPasswordSet(),
                                false,
                                current.realNameStatus(),
                                current.realNameVerified())));
    }

    public Mono<UpstreamResponse> submitRealName(
            WebSession webSession,
            ServerWebExchange exchange,
            MediaType contentType,
            Flux<DataBuffer> body,
            String idempotencyKey) {
        return upstream.multipart(
                        webSession,
                        exchange.getRequest(),
                        "/api/v1/real-name-verifications",
                        contentType,
                        body,
                        idempotencyKey)
                .flatMap(response -> {
                    if (!response.successful()) {
                        return Mono.just(response);
                    }
                    String status = firstText(parse(response), "status");
                    String normalized = status == null ? "PENDING" : status;
                    return updateSessionWhenSuccessful(
                            webSession,
                            response,
                            current -> new ConsumerSession(
                                    current.consumerId(),
                                    current.maskedPhone(),
                                    current.displayName(),
                                    current.payPasswordSet(),
                                    current.onboardingRequired(),
                                    normalized,
                                    "VERIFIED".equals(normalized)));
                });
    }

    public Mono<UpstreamResponse> confirmPhoneChange(
            WebSession webSession,
            ServerWebExchange exchange,
            String mobile,
            String challengeId,
            String code,
            String idempotencyKey) {
        return upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.PUT,
                        "/api/v1/users/me/phone",
                        Map.of(),
                        Map.of("challengeId", challengeId, "code", code),
                        idempotencyKey)
                .flatMap(response -> updateSessionWhenSuccessful(
                        webSession,
                        response,
                        current -> new ConsumerSession(
                                current.consumerId(),
                                MobileMasking.mask(mobile),
                                current.displayName(),
                                current.payPasswordSet(),
                                current.onboardingRequired(),
                                current.realNameStatus(),
                                current.realNameVerified())));
    }

    public Mono<UpstreamResponse> requestPaymentPasswordChange(
            WebSession webSession,
            ServerWebExchange exchange,
            String mobile,
            String idempotencyKey) {
        return session(webSession).loadDeviceId()
                .switchIfEmpty(Mono.error(deviceBindingRequired()))
                .flatMap(deviceId -> upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.POST,
                        "/api/v1/users/me/payment-password-change-challenges",
                        Map.of(),
                        Map.of("mobile", mobile, "deviceId", deviceId),
                        idempotencyKey));
    }

    public Mono<UpstreamResponse> verifyPaymentPasswordChange(
            WebSession webSession,
            ServerWebExchange exchange,
            String challengeId,
            String code,
            String idempotencyKey) {
        return session(webSession).loadDeviceId()
                .switchIfEmpty(Mono.error(deviceBindingRequired()))
                .flatMap(deviceId -> upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.POST,
                        "/api/v1/users/me/payment-password-change-challenges/"
                                + challengeId + "/verifications",
                        Map.of(),
                        Map.of("code", code, "deviceId", deviceId),
                        idempotencyKey));
    }

    public Mono<UpstreamResponse> changePaymentPassword(
            WebSession webSession,
            ServerWebExchange exchange,
            String verificationToken,
            String newPassword,
            String idempotencyKey) {
        SessionTokenStore sessions = session(webSession);
        return sessions.loadDeviceId()
                .switchIfEmpty(Mono.error(deviceBindingRequired()))
                .flatMap(deviceId -> upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.POST,
                        "/api/v1/users/me/payment-password-changes",
                        Map.of(),
                        Map.of(
                                "verificationToken", verificationToken,
                                "newPassword", newPassword,
                                "deviceId", deviceId),
                        idempotencyKey))
                .flatMap(response -> {
                    if (!response.successful()) {
                        return Mono.just(response);
                    }
                    return refreshOnce(sessions, requestIdOf(exchange))
                            .onErrorResume(ignored -> Mono.empty())
                            .then(updateSessionWhenSuccessful(
                                    webSession,
                                    response,
                                    current -> new ConsumerSession(
                                            current.consumerId(),
                                            current.maskedPhone(),
                                            current.displayName(),
                                            true,
                                            current.onboardingRequired(),
                                            current.realNameStatus(),
                                            current.realNameVerified())));
                });
    }

    private Mono<UpstreamResponse> updateSessionWhenSuccessful(
            WebSession webSession,
            UpstreamResponse response,
            UnaryOperator<ConsumerSession> update) {
        if (!response.successful()) {
            return Mono.just(response);
        }
        SessionTokenStore sessions = session(webSession);
        return sessions.loadConsumerSession()
                .flatMap(current -> sessions.storeConsumerSession(update.apply(current)))
                .thenReturn(response);
    }

    private static UpstreamProblemException deviceBindingRequired() {
        return new UpstreamProblemException(
                HttpStatus.CONFLICT,
                "DEVICE_BINDING_REQUIRED",
                "请重新登录后再进行安全操作");
    }

    // --------------------------------------------------------- bank funding

    public Mono<UpstreamResponse> queryBankBalance(
            WebSession webSession,
            ServerWebExchange exchange,
            String cardId,
            String paymentPassword,
            String rootIdempotencyKey) {
        SessionTokenStore sessions = session(webSession);
        return sessions.loadDeviceId()
                .switchIfEmpty(Mono.error(deviceBindingRequired()))
                .flatMap(deviceId -> tokenFor(sessions, requestIdOf(exchange))
                        .flatMap(accessToken -> identityConsumer.issuePaymentAuthorization(
                                accessToken,
                                operationKey(rootIdempotencyKey, "authorize"),
                                "BANK_CARD_BALANCE_QUERY",
                                cardId,
                                0,
                                deviceId,
                                paymentPassword,
                                requestIdOf(exchange))))
                .flatMap(authorization -> upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.POST,
                        "/api/v1/bank-cards/" + cardId + "/balance-queries",
                        Map.of(),
                        Map.of("paymentAuthToken", authorization.paymentAuthToken()),
                        operationKey(rootIdempotencyKey, "query")));
    }

    public Mono<UpstreamResponse> recharge(
            WebSession webSession,
            ServerWebExchange exchange,
            String bankCardId,
            long amountFen,
            String paymentPassword,
            String rootIdempotencyKey) {
        return prepareAuthorizeAndConfirmFunding(
                webSession,
                exchange,
                bankCardId,
                amountFen,
                paymentPassword,
                rootIdempotencyKey,
                true);
    }

    public Mono<UpstreamResponse> withdraw(
            WebSession webSession,
            ServerWebExchange exchange,
            String bankCardId,
            long amountFen,
            String paymentPassword,
            String rootIdempotencyKey) {
        return prepareAuthorizeAndConfirmFunding(
                webSession,
                exchange,
                bankCardId,
                amountFen,
                paymentPassword,
                rootIdempotencyKey,
                false);
    }

    private Mono<UpstreamResponse> prepareAuthorizeAndConfirmFunding(
            WebSession webSession,
            ServerWebExchange exchange,
            String bankCardId,
            long amountFen,
            String paymentPassword,
            String rootIdempotencyKey,
            boolean recharge) {
        if (amountFen < 1 || amountFen > 1_000_000) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST,
                    "FUNDING_AMOUNT_INVALID",
                    "金额必须在 0.01 元到 10000 元之间"));
        }
        String createPath = recharge ? "/api/v1/recharge-intents" : "/api/v1/withdrawal-orders";
        return upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.POST,
                        createPath,
                        Map.of(),
                        Map.of("bankCardId", bankCardId, "amountCent", amountFen),
                        operationKey(rootIdempotencyKey, "create"))
                .flatMap(created -> {
                    if (!created.successful()) {
                        throw translate(created, recharge
                                ? "RECHARGE_PREPARE_FAILED"
                                : "WITHDRAWAL_PREPARE_FAILED");
                    }
                    Map<String, Object> document = parse(created);
                    String orderId = firstText(
                            document, recharge ? "rechargeId" : "withdrawalId");
                    if (orderId == null) {
                        throw new UpstreamProblemException(
                                HttpStatus.BAD_GATEWAY,
                                "FUNDING_ORDER_INVALID",
                                "上游未返回资金订单标识");
                    }
                    String status = firstText(document, "status");
                    String confirmationStatus = recharge ? "PENDING_CONFIRMATION" : "PROCESSING";
                    if (status != null && !confirmationStatus.equals(status)) {
                        return Mono.just(created);
                    }
                    SessionTokenStore sessions = session(webSession);
                    return sessions.loadDeviceId()
                            .switchIfEmpty(Mono.error(deviceBindingRequired()))
                            .flatMap(deviceId -> tokenFor(sessions, requestIdOf(exchange))
                                    .flatMap(accessToken -> identityConsumer.issuePaymentAuthorization(
                                            accessToken,
                                            operationKey(rootIdempotencyKey, "authorize"),
                                            recharge ? "RECHARGE_ORDER" : "WITHDRAWAL_ORDER",
                                            orderId,
                                            amountFen,
                                            deviceId,
                                            paymentPassword,
                                            requestIdOf(exchange))))
                            .flatMap(authorization -> upstream.call(
                                    webSession,
                                    exchange.getRequest(),
                                    HttpMethod.POST,
                                    recharge
                                            ? "/api/v1/recharge-intents/" + orderId + "/confirm"
                                            : "/api/v1/withdrawal-orders/" + orderId + "/confirm",
                                    Map.of(),
                                    Map.of("paymentAuthToken", authorization.paymentAuthToken()),
                                    operationKey(rootIdempotencyKey, "confirm")));
                });
    }

    private static String operationKey(String root, String operation) {
        return UUID.nameUUIDFromBytes(
                        (root + ":" + operation).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String requestIdOf(ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId;
    }

    // ------------------------------------------------------- sandbox transfers

    /**
     * Step 1 of the three-step transfer flow, kept deliberately unmerged: resolve the payee, create
     * the upstream transfer intent, and hand the intent id back so the browser can collect the
     * payment password in a separate request.
     */
    public Mono<TransferPreparation> prepareTransfer(
            WebSession webSession,
            ServerWebExchange exchange,
            String payeeIdentifier,
            long amountFen,
            String remark,
            String requestId) {
        SessionTokenStore sessions = session(webSession);
        return resolvePayee(webSession, exchange, payeeIdentifier, requestId)
                .flatMap(payee -> upstream.call(
                                webSession,
                                exchange.getRequest(),
                                HttpMethod.POST,
                                "/api/v1/transfers",
                                Map.of(),
                                Map.of(
                                        "receiverUserId", payee.recipientUserId(),
                                        "amountCent", amountFen,
                                        "remark", remark == null ? "" : remark,
                                        // 上游 TransferService 只接受 FORM / AI / PERSONAL_COLLECTION_CODE。
                                        // 这里曾写 "H5"（不在允许集内），线上表现为 400 INVALID_TRANSFER_SOURCE，
                                        // 转账整条链路直接不可用。H5 的转账表单就是 FORM。
                                        "source", "FORM"),
                                UUID.randomUUID().toString())
                        .map(response -> toPreparation(response, payee))
                        .flatMap(preparation -> sessions
                                .storePreparedTransfer(
                                        preparation.transferIntentId(), preparation.amountFen())
                                .thenReturn(preparation)));
    }

    private Mono<ResolvedPayee> resolvePayee(
            WebSession session, ServerWebExchange exchange, String payeeIdentifier, String requestId) {
        if (payeeIdentifier == null || payeeIdentifier.isBlank()) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "PAYEE_IDENTIFIER_REQUIRED", "请填写收款人手机号"));
        }
        String identifier = payeeIdentifier.trim();
        if (identifier.matches("^1[3-9]\\d{9}$")) {
            // Identity resolves the HMAC of the mobile and returns only masked presentation data.
            return upstream.call(
                            session,
                            exchange.getRequest(),
                            HttpMethod.POST,
                            "/api/v1/transfer-recipients/resolve",
                            Map.of(),
                            Map.of("mobile", identifier),
                            null)
                    .map(this::toResolvedPayee);
        }
        if (identifier.startsWith("minipay://friend/")) {
            String miniPayNo = identifier.substring("minipay://friend/".length());
            return upstream.call(
                            session,
                            exchange.getRequest(),
                            HttpMethod.GET,
                            "/api/v1/users/qr/" + miniPayNo,
                            Map.of(),
                            null,
                            null)
                    .map(this::toResolvedPayee);
        }
        return Mono.error(new UpstreamProblemException(
                HttpStatus.BAD_REQUEST,
                "PAYEE_IDENTIFIER_UNSUPPORTED",
                "仅支持手机号或好友二维码"));
    }

    private ResolvedPayee toResolvedPayee(UpstreamResponse response) {
        if (!response.successful()) {
            throw translate(response, "PAYEE_RESOLUTION_FAILED");
        }
        Map<String, Object> document = parse(response);
        String recipientUserId = firstText(document, "recipientUserId", "userId");
        if (recipientUserId == null) {
            throw new UpstreamProblemException(
                    HttpStatus.BAD_GATEWAY, "PAYEE_RESOLUTION_INVALID", "上游未返回收款人标识");
        }
        String display = firstText(document, "phoneMasked", "maskedMobile", "nickname");
        return new ResolvedPayee(recipientUserId, display == null ? "" : display);
    }

    private TransferPreparation toPreparation(UpstreamResponse response, ResolvedPayee payee) {
        if (!response.successful()) {
            throw translate(response, "TRANSFER_PREPARE_FAILED");
        }
        Map<String, Object> document = parse(response);
        String intentId = firstText(document, "intentId", "transferIntentId");
        if (intentId == null) {
            throw new UpstreamProblemException(
                    HttpStatus.BAD_GATEWAY, "TRANSFER_INTENT_INVALID", "上游未返回转账意图标识");
        }
        return new TransferPreparation(
                intentId, payee.display(), number(document, "amountCent"),
                firstText(document, "expiresAt"));
    }

    /**
     * Steps 2 and 3: exchange the payment password for a single-use authorization token at Identity
     * and hand that token straight to Payment. The two upstream calls are never merged and the
     * password never leaves this method.
     */
    public Mono<TransferConfirmation> confirmTransfer(
            WebSession webSession,
            ServerWebExchange exchange,
            String intentId,
            long amountFen,
            String paymentPassword,
            String requestId) {
        SessionTokenStore sessions = session(webSession);
        // The amount bounds the one-time authorization, so it must never be defaulted to zero: a
        // missing field would otherwise mint a zero-fen authorization for a real transfer.
        if (amountFen < 1) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "TRANSFER_AMOUNT_INVALID", "转账金额必须大于 0"));
        }
        return requirePreparedAmount(sessions, intentId, amountFen, "TRANSFER_AMOUNT_MISMATCH")
                .then(sessions.loadDeviceId()
                        .switchIfEmpty(Mono.error(new UpstreamProblemException(
                                HttpStatus.CONFLICT,
                                "DEVICE_BINDING_REQUIRED",
                                "请重新登录后再进行资金操作")))
                        .flatMap(deviceId -> tokenFor(sessions, requestId)
                                .flatMap(accessToken -> identityConsumer.issuePaymentAuthorization(
                                                accessToken,
                                                UUID.randomUUID().toString(),
                                                "TRANSFER_INTENT",
                                                intentId,
                                                amountFen,
                                                deviceId,
                                                paymentPassword,
                                                requestId)
                                        .flatMap(authorization -> upstream.call(
                                                webSession,
                                                exchange.getRequest(),
                                                HttpMethod.POST,
                                                "/api/v1/transfers/" + intentId + "/confirm",
                                                Map.of(),
                                                Map.of("paymentAuthToken",
                                                        authorization.paymentAuthToken()),
                                                UUID.randomUUID().toString())))))
                .map(this::toConfirmation);
    }

    // ------------------------------------------------------ sandbox payments

    /**
     * Step 1 of 扫码付款: resolve the collection code the payer scanned.
     *
     * <p>The upstream document is relayed verbatim because the two resolution shapes differ: a
     * merchant code answers with {@code MERCHANT_COLLECTION} plus a single-use {@code resolutionId},
     * while a personal code answers with the receiver's presentation data and no id. The browser
     * needs both fields to decide what it can offer.
     */
    public Mono<UpstreamResponse> resolveCollectionCode(
            WebSession session,
            ServerWebExchange exchange,
            String deepLink,
            String merchantToken,
            String requestId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (deepLink != null && !deepLink.isBlank()) {
            body.put("deepLink", deepLink.trim());
        }
        if (merchantToken != null && !merchantToken.isBlank()) {
            body.put("merchantToken", merchantToken.trim());
        }
        if (body.isEmpty()) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "COLLECTION_CODE_REQUIRED", "请填写收款码内容"));
        }
        return upstream.call(
                session,
                exchange.getRequest(),
                HttpMethod.POST,
                "/api/v1/scan-resolutions",
                Map.of(),
                body,
                null);
    }

    /**
     * Step 2: create the merchant payment order. Payment Service owns the order and therefore the
     * authoritative amount; the amount sent here is remembered only so the later confirmation can be
     * bound to what this session actually prepared.
     *
     * <p>The subject is a fixed server-side label on purpose: it is rendered in the merchant and
     * operations consoles, so it must never carry text supplied by the browser.
     */
    public Mono<MerchantPaymentPreparation> prepareMerchantPayment(
            WebSession webSession,
            ServerWebExchange exchange,
            String resolutionId,
            long amountFen,
            String requestId) {
        if (resolutionId == null || resolutionId.isBlank()) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "PAYMENT_RESOLUTION_REQUIRED", "请先扫码识别收款方"));
        }
        if (amountFen < 1) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_INVALID", "付款金额必须大于 0"));
        }
        SessionTokenStore sessions = session(webSession);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("amountCent", amountFen);
        body.put("subject", PAYMENT_SUBJECT);
        body.put("paymentMethod", "WALLET_BALANCE");
        body.put("resolutionId", resolutionId);
        return upstream.call(
                        webSession,
                        exchange.getRequest(),
                        HttpMethod.POST,
                        "/api/v1/payment-orders",
                        Map.of(),
                        body,
                        UUID.randomUUID().toString())
                .map(this::toMerchantPreparation)
                .flatMap(preparation -> sessions
                        .storePreparedPayment(
                                preparation.paymentOrderId(), preparation.amountFen())
                        .thenReturn(preparation));
    }

    /**
     * Step 3: payment password to Identity for a single-use authorization bound to this payment
     * order, then hand that token to Payment. Exactly like a transfer, the two upstream calls are
     * never merged and the password never leaves this method.
     */
    public Mono<MerchantPaymentConfirmation> confirmMerchantPayment(
            WebSession webSession,
            ServerWebExchange exchange,
            String paymentOrderId,
            long amountFen,
            String paymentPassword,
            String requestId) {
        SessionTokenStore sessions = session(webSession);
        if (amountFen < 1) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_INVALID", "付款金额必须大于 0"));
        }
        return requirePreparedAmount(sessions, paymentOrderId, amountFen, "PAYMENT_AMOUNT_MISMATCH")
                .then(sessions.loadDeviceId()
                        .switchIfEmpty(Mono.error(new UpstreamProblemException(
                                HttpStatus.CONFLICT,
                                "DEVICE_BINDING_REQUIRED",
                                "请重新登录后再进行资金操作")))
                        .flatMap(deviceId -> tokenFor(sessions, requestId)
                                .flatMap(accessToken -> identityConsumer.issuePaymentAuthorization(
                                                accessToken,
                                                UUID.randomUUID().toString(),
                                                "PAYMENT_ORDER",
                                                paymentOrderId,
                                                amountFen,
                                                deviceId,
                                                paymentPassword,
                                                requestId)
                                        .flatMap(authorization -> upstream.call(
                                                webSession,
                                                exchange.getRequest(),
                                                HttpMethod.POST,
                                                "/api/v1/payment-orders/" + paymentOrderId + "/confirm",
                                                Map.of(),
                                                Map.of("paymentAuthToken",
                                                        authorization.paymentAuthToken()),
                                                UUID.randomUUID().toString())))))
                .map(this::toMerchantConfirmation);
    }

    private MerchantPaymentPreparation toMerchantPreparation(UpstreamResponse response) {
        if (!response.successful()) {
            throw translate(response, "PAYMENT_PREPARE_FAILED");
        }
        Map<String, Object> document = parse(response);
        String paymentOrderId = firstText(document, "paymentOrderId");
        if (paymentOrderId == null) {
            throw new UpstreamProblemException(
                    HttpStatus.BAD_GATEWAY, "PAYMENT_ORDER_INVALID", "上游未返回支付单标识");
        }
        return new MerchantPaymentPreparation(
                paymentOrderId,
                firstText(document, "paymentOrderNo"),
                number(document, "amountCent"),
                firstText(document, "expiresAt"));
    }

    private MerchantPaymentConfirmation toMerchantConfirmation(UpstreamResponse response) {
        if (!response.successful()) {
            throw translate(response, "PAYMENT_CONFIRM_FAILED");
        }
        Map<String, Object> document = parse(response);
        String status = firstText(document, "status");
        return new MerchantPaymentConfirmation(
                firstText(document, "paymentOrderNo"),
                status == null ? "PROCESSING" : status,
                firstText(document, "failureCode"));
    }

    /**
     * Binds the confirmation to the amount this session prepared. Payment Service owns the intent,
     * so its amount stays authoritative; this check only stops a tampered or stale browser from
     * requesting an authorization for a different amount.
     */
    /**
     * Binds the confirmation to the amount this session prepared. Payment Service owns the intent or
     * order, so its amount stays authoritative; this check only stops a tampered or stale browser
     * from requesting an authorization for a different amount.
     */
    private Mono<Void> requirePreparedAmount(
            SessionTokenStore sessions, String intentId, long amountFen, String mismatchCode) {
        return sessions.loadPreparedAmount(intentId)
                .map(prepared -> prepared == amountFen)
                .defaultIfEmpty(false)
                .flatMap(matches -> matches
                        ? Mono.empty()
                        : Mono.error(new UpstreamProblemException(
                                HttpStatus.CONFLICT,
                                mismatchCode,
                                "确认金额与已创建的" + ("PAYMENT_AMOUNT_MISMATCH".equals(mismatchCode)
                                        ? "支付单" : "转账意图") + "不一致，请重新发起")));
    }

    public Mono<UpstreamResponse> cancelTransfer(
            WebSession session, ServerWebExchange exchange, String intentId) {
        return upstream.call(
                session,
                exchange.getRequest(),
                HttpMethod.DELETE,
                "/api/v1/transfers/" + intentId,
                Map.of(),
                null,
                null);
    }

    public Mono<UpstreamResponse> transferOrder(
            WebSession session, ServerWebExchange exchange, String transferNo) {
        return upstream.call(
                session,
                exchange.getRequest(),
                HttpMethod.GET,
                "/api/v1/transfer-orders/" + transferNo,
                Map.of(),
                null,
                null);
    }

    /**
     * Transfer history. Payment Service exposes no transfer list, so the authoritative
     * consumer-facing history is Wallet's per-counterparty transfer record feed and the BFF relays
     * it while keeping a stable cursor/limit surface for the browser.
     */
    public Mono<UpstreamResponse> transferHistory(
            WebSession session,
            ServerWebExchange exchange,
            String counterpartyUserId,
            String cursor,
            String limit) {
        if (counterpartyUserId == null || counterpartyUserId.isBlank()) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST,
                    "COUNTERPARTY_REQUIRED",
                    "查询转账记录需要 counterpartyUserId"));
        }
        return upstream.call(
                session,
                exchange.getRequest(),
                HttpMethod.GET,
                "/api/v1/wallets/me/transfer-records",
                Map.of(
                        "counterpartyUserId", counterpartyUserId,
                        "page", cursor == null || cursor.isBlank() ? "1" : cursor,
                        "size", limit == null || limit.isBlank() ? "20" : limit),
                null,
                null);
    }

    private Mono<String> tokenFor(SessionTokenStore sessions, String requestId) {
        return sessions.loadTokens()
                .filter(ConsumerTokens::hasAccessToken)
                .map(ConsumerTokens::accessToken)
                .switchIfEmpty(Mono.defer(() -> refreshOnce(sessions, requestId)));
    }

    private Mono<String> refreshOnce(SessionTokenStore sessions, String requestId) {
        return sessions.loadTokens()
                .filter(ConsumerTokens::hasRefreshToken)
                .switchIfEmpty(Mono.error(SessionRequiredException.expired()))
                .flatMap(tokens -> identity.refresh(tokens.refreshToken(), requestId))
                .flatMap(refreshed -> sessions
                        .storeTokens(new ConsumerTokens(
                                refreshed.accessToken(), refreshed.refreshToken()))
                        .thenReturn(refreshed.accessToken()));
    }

    private TransferConfirmation toConfirmation(UpstreamResponse response) {
        if (!response.successful()) {
            throw translate(response, "TRANSFER_CONFIRM_FAILED");
        }
        Map<String, Object> document = parse(response);
        String status = firstText(document, "status");
        return new TransferConfirmation(
                firstText(document, "transferId", "transferNo"),
                status == null ? "PROCESSING" : status,
                firstText(document, "failureCode"));
    }

    private static Map<String, Object> parse(UpstreamResponse response) {
        try {
            return DOCUMENT_READER.readValue(
                    response.body() == null || response.body().isBlank() ? "{}" : response.body(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static UpstreamProblemException translate(UpstreamResponse response, String fallbackCode) {
        Map<String, Object> document = parse(response);
        String code = firstText(document, "code");
        String detail = firstText(document, "detail", "title");
        HttpStatus status = HttpStatus.resolve(response.status()) == null
                ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(response.status());
        return new UpstreamProblemException(
                status, code == null ? fallbackCode : code, detail);
    }

    private static String firstText(Map<String, Object> document, String... keys) {
        for (String key : keys) {
            Object value = document.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static long number(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private record ResolvedPayee(String recipientUserId, String display) {
    }

    public record TransferPreparation(
            String transferIntentId, String payeeMasked, long amountFen, String expiresAt) {
    }

    public record TransferConfirmation(String transferNo, String status, String failureCode) {
    }

    public record MerchantPaymentPreparation(
            String paymentOrderId, String paymentOrderNo, long amountFen, String expiresAt) {
    }

    public record MerchantPaymentConfirmation(String paymentOrderNo, String status, String failureCode) {
    }
}
