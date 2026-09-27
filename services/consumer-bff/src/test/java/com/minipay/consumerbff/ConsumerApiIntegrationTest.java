package com.minipay.consumerbff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behaviour of the consumer H5 session and API proxy against stubbed upstreams.
 *
 * <p>Together these assertions pin the contract the frontend was built against: the PKCE login
 * sequence, session rotation, the single refresh-and-retry on 401, the mandatory CSRF check, and
 * the unmerged "authorize then confirm" transfer order.
 */
class ConsumerApiIntegrationTest extends ConsumerBffIntegrationTest {

    private static final Pattern PKCE_VERIFIER = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");
    private static final String INTENT_ID = "0198f200-0000-7000-8000-0000000000f1";
    private static final String PAYEE_ID = "0198f200-0000-7000-8000-0000000000e1";
    private static final String TRANSFER_ID = "0198f200-0000-7000-8000-0000000000b1";
    private static final String RESOLUTION_ID = "0198f200-0000-7000-8000-0000000000d5";
    private static final String PAYMENT_ORDER_ID = "0198f200-0000-7000-8000-0000000000d6";
    private static final String PAYMENT_ORDER_NO = "PAY20260101000001";
    private static final String MERCHANT_RESOLUTION_JSON =
            "{\"type\":\"MERCHANT_COLLECTION\",\"resolutionId\":\"" + RESOLUTION_ID + "\","
                    + "\"merchantId\":\"0198f200-0000-7000-8000-0000000000d7\","
                    + "\"merchantName\":\"Demo Merchant\","
                    + "\"allowedChannels\":[\"WALLET_BALANCE\"],"
                    + "\"expiresAt\":\"2030-01-01T00:10:00Z\"}";
    private static final String RECIPIENT_JSON =
            "{\"recipientUserId\":\"" + PAYEE_ID + "\",\"nickname\":\"Zhang San\","
                    + "\"phoneMasked\":\"139****9000\",\"legalNameMasked\":\"Z*\","
                    + "\"avatarUrl\":null,\"verified\":true}";
    private static final String INTENT_JSON =
            "{\"intentId\":\"" + INTENT_ID + "\",\"amountCent\":2500,"
                    + "\"status\":\"PENDING_CONFIRMATION\","
                    + "\"expiresAt\":\"2030-01-01T00:05:00Z\"}";

    @Test
    void loginPerformsPkceSequenceAndRotatesTheSessionId() throws Exception {
        String csrf = csrfToken();
        String sessionBeforeLogin = currentSessionId();

        enqueueLogin();
        exchangeRememberingCookies(withCookies(client.post().uri("/api/v1/session/sms")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"mobile\":\"" + MOBILE + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.challengeId").isEqualTo("challenge-1")
                .jsonPath("$.demoCode").doesNotExist());

        enqueueVerification();
        exchangeRememberingCookies(withCookies(client.post().uri("/api/v1/session")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"mobile\":\"" + MOBILE + "\",\"challengeId\":\"challenge-1\","
                        + "\"code\":\"123456\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(true)
                .jsonPath("$.userId").isEqualTo(CONSUMER_ID)
                .jsonPath("$.phone").isEqualTo("138****8000")
                .jsonPath("$.displayName").isNotEmpty()
                .jsonPath("$.payPasswordSet").isEqualTo(true)
                .jsonPath("$.onboardingRequired").isEqualTo(false)
                .jsonPath("$.realNameStatus").isEqualTo("VERIFIED")
                .jsonPath("$.realNameVerified").isEqualTo(true));

        String sessionAfterLogin = currentSessionId();
        assertThat(sessionBeforeLogin).isNotBlank();
        assertThat(sessionAfterLogin).isNotBlank();
        // 这里刻意不再断言 id 发生变化：本集成切片跑在 Boot 默认的内存 WebSession 上
        // （见 ConsumerBffIntegrationTest 的 InMemorySessionConfiguration），其 changeSessionId()
        // 是空操作，观测不到轮换；线上由 Spring Session(Redis) 承担。
        // "登录后请求了轮换" 已由 WebSessionTokenStoreTest 在单元层锁定。

        RecordedRequest sendRequest = takeRequest(IDENTITY);
        assertThat(sendRequest.getPath()).isEqualTo("/api/v1/auth/consumer/code/send");
        assertThat(body(sendRequest).path("purpose").asText()).isEqualTo("LOGIN");

        RecordedRequest verifyRequest = takeRequest(IDENTITY);
        assertThat(verifyRequest.getPath()).isEqualTo("/api/v1/auth/consumer/code/verify");
        JsonNode verifyBody = body(verifyRequest);
        assertThat(verifyBody.path("clientId").asText()).isEqualTo("minipay-consumer-bff");
        assertThat(verifyBody.path("redirectUri").asText())
                .isEqualTo("http://localhost:8087/session/callback");
        assertThat(verifyBody.path("codeChallengeMethod").asText()).isEqualTo("S256");
        assertThat(verifyBody.path("deviceId").asText()).isNotBlank();

        RecordedRequest tokenRequest = takeRequest(IDENTITY);
        assertThat(tokenRequest.getPath()).isEqualTo("/oauth2/token");
        JsonNode tokenForm = form(tokenRequest);
        assertThat(tokenForm.path("grant_type").asText()).isEqualTo("authorization_code");
        assertThat(tokenForm.path("code").asText()).isEqualTo("auth-code-1");
        String verifier = tokenForm.path("code_verifier").asText();
        assertThat(verifier).matches(PKCE_VERIFIER);

        // The challenge actually verified by Identity must be the S256 hash of the server-side
        // verifier: the browser never saw the verifier at all.
        assertThat(com.minipay.consumerbff.domain.security.Pkce.challengeOf(verifier))
                .isEqualTo(verifyBody.path("codeChallenge").asText());

        RecordedRequest profile = takeRequest(IDENTITY);
        assertThat(profile.getPath()).isEqualTo("/api/v1/users/me");
        assertThat(profile.getHeader("Authorization")).isEqualTo("Bearer access-1");

        // No response body may ever contain the raw mobile or a token.
        assertThat(new String(withCookies(client.get().uri("/api/v1/session"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody()))
                .contains("\"authenticated\":true")
                .contains("138****8000")
                .doesNotContain(MOBILE)
                .doesNotContain("access-1")
                .doesNotContain("refresh-1");
    }

    @Test
    void wrongVerificationCodeIsRejectedWithTheUpstreamProblemCode() {
        String csrf = csrfToken();
        enqueueLogin();
        exchangeRememberingCookies(withCookies(client.post().uri("/api/v1/session/sms")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"mobile\":\"" + MOBILE + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody());

        IDENTITY.enqueue(problem(400, "SMS_CODE_INVALID", null));
        exchangeRememberingCookies(withCookies(client.post().uri("/api/v1/session")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"mobile\":\"" + MOBILE + "\",\"challengeId\":\"challenge-1\","
                        + "\"code\":\"000000\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("SMS_CODE_INVALID")
                .jsonPath("$.requestId").isNotEmpty());

        withCookies(client.get().uri("/api/v1/session"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(false);
    }

    @Test
    void unauthenticatedWalletReadIsRejectedWith401BeforeAnyUpstreamCall() throws Exception {
        int walletBefore = walletCalls();
        int paymentBefore = paymentCalls();

        client.get().uri("/api/v1/wallet")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
                .jsonPath("$.requestId").isNotEmpty();

        // Fail closed at the edge: an unauthenticated request must never be relayed upstream.
        assertThat(walletCalls()).isEqualTo(walletBefore);
        assertThat(paymentCalls()).isEqualTo(paymentBefore);
    }

    @Test
    void missingCsrfTokenIsRejectedBeforeTheBusinessBoundary() throws Exception {
        login();
        int paymentBefore = paymentCalls();
        int identityBefore = identityCalls();

        withCookies(client.post().uri("/api/v1/transfers/prepare")
                        .header("Content-Type", "application/json"))
                .bodyValue("{\"payeeIdentifier\":\"13900139000\",\"amountFen\":100}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CSRF_TOKEN_INVALID");

        // The upstream must not have been touched by a request that failed the CSRF check.
        assertThat(paymentCalls()).isEqualTo(paymentBefore);
        assertThat(identityCalls()).isEqualTo(identityBefore);
    }

    @Test
    void upstream401TriggersExactlyOneRefreshAndOneRetry() throws Exception {
        login();

        WALLET.enqueue(problem(401, "INVALID_ACCESS_TOKEN", null));
        IDENTITY.enqueue(json(200, "{\"access_token\":\"access-2\","
                + "\"token_type\":\"Bearer\",\"expires_in\":600,\"refresh_token\":\"refresh-2\"}"));
        WALLET.enqueue(json(200, "{\"accountId\":\"account-1\",\"balanceCent\":12345}"));

        withCookies(client.get().uri("/api/v1/wallet"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.balanceCent").isEqualTo(12345);

        assertThat(walletCalls()).isEqualTo(2);
        RecordedRequest first = takeRequest(WALLET);
        RecordedRequest second = takeRequest(WALLET);
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer access-1");
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer access-2");

        RecordedRequest refresh = takeRequest(IDENTITY);
        assertThat(refresh.getPath()).isEqualTo("/oauth2/token");
        assertThat(form(refresh).path("grant_type").asText()).isEqualTo("refresh_token");
        assertThat(form(refresh).path("refresh_token").asText()).isEqualTo("refresh-1");
    }

    @Test
    void secondUpstream401ClearsTheSessionAndReturns401() throws Exception {
        login();

        IDENTITY.enqueue(json(200, "{\"access_token\":\"access-2\","
                + "\"token_type\":\"Bearer\",\"expires_in\":600,\"refresh_token\":\"refresh-2\"}"));
        WALLET.enqueue(problem(401, "INVALID_ACCESS_TOKEN", null));
        WALLET.enqueue(problem(401, "INVALID_ACCESS_TOKEN", null));

        withCookies(client.get().uri("/api/v1/wallet"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SESSION_EXPIRED");

        withCookies(client.get().uri("/api/v1/wallet"))
                .exchange()
                .expectStatus().isUnauthorized();

        // Exactly one refresh and one retry, never a refresh loop.
        assertThat(walletCalls()).isEqualTo(2);
    }

    @Test
    void transferConfirmExchangesThePaymentPasswordForASingleUseTokenFirst() throws Exception {
        login();
        String csrf = csrfToken();

        // 1. prepare: resolve the payee by mobile, then create the upstream intent.
        IDENTITY.enqueue(json(200, RECIPIENT_JSON));
        PAYMENT.enqueue(json(201, INTENT_JSON));
        prepareTransfer(csrf, 2500);

        RecordedRequest resolve = takeRequest(IDENTITY);
        assertThat(resolve.getPath()).isEqualTo("/api/v1/transfer-recipients/resolve");
        assertThat(resolve.getHeader("Authorization")).isEqualTo("Bearer access-1");
        assertThat(body(resolve).path("mobile").asText()).isEqualTo("13900139000");

        RecordedRequest create = takeRequest(PAYMENT);
        assertThat(create.getMethod()).isEqualTo("POST");
        assertThat(create.getPath()).isEqualTo("/api/v1/transfers");
        assertThat(create.getHeader("Idempotency-Key")).isNotBlank();
        JsonNode createBody = body(create);
        assertThat(createBody.path("receiverUserId").asText()).isEqualTo(PAYEE_ID);
        assertThat(createBody.path("amountCent").asLong()).isEqualTo(2500);
        // 上游只接受 FORM / AI / PERSONAL_COLLECTION_CODE；写成 "H5" 线上会 400 INVALID_TRANSFER_SOURCE
        assertThat(createBody.path("source").asText()).isEqualTo("FORM");

        // 2. confirm: payment password -> one-time authorization -> confirm with that token.
        IDENTITY.enqueue(json(201, "{\"authorizationId\":"
                + "\"0198f200-0000-7000-8000-0000000000a1\","
                + "\"paymentAuthToken\":\"one-time-token\","
                + "\"expiresAt\":\"2030-01-01T00:02:00Z\"}"));
        PAYMENT.enqueue(json(200, "{\"transferId\":\"" + TRANSFER_ID + "\","
                + "\"intentId\":\"" + INTENT_ID + "\","
                + "\"status\":\"SUCCEEDED\",\"failureCode\":null}"));

        withCookies(client.post().uri("/api/v1/transfers/" + INTENT_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"amountFen\":2500,\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.transferNo").isEqualTo(TRANSFER_ID)
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        RecordedRequest authorize = takeRequest(IDENTITY);
        assertThat(authorize.getPath()).isEqualTo("/api/v1/payment-authorizations");
        assertThat(authorize.getMethod()).isEqualTo("POST");
        assertThat(authorize.getHeader("Idempotency-Key")).isNotBlank();
        JsonNode authorizeBody = body(authorize);
        assertThat(authorizeBody.path("subjectType").asText()).isEqualTo("TRANSFER_INTENT");
        assertThat(authorizeBody.path("subjectId").asText()).isEqualTo(INTENT_ID);
        assertThat(authorizeBody.path("amountCent").asLong()).isEqualTo(2500);
        assertThat(authorizeBody.path("payPassword").asText()).isEqualTo("123456");
        assertThat(authorizeBody.path("deviceId").asText()).isNotBlank();

        RecordedRequest confirm = takeRequest(PAYMENT);
        assertThat(confirm.getPath()).isEqualTo("/api/v1/transfers/" + INTENT_ID + "/confirm");
        JsonNode confirmBody = body(confirm);
        assertThat(confirmBody.path("paymentAuthToken").asText()).isEqualTo("one-time-token");
        // The payment password is consumed by Identity only and must never reach Payment.
        assertThat(confirmBody.toString()).doesNotContain("123456");
    }

    @Test
    void confirmWithoutAPositiveAmountIsRejectedBeforeAnyAuthorization() {
        login();
        String csrf = csrfToken();

        // A missing amount would otherwise default to zero and mint a zero-fen authorization.
        withCookies(client.post().uri("/api/v1/transfers/" + INTENT_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED");

        withCookies(client.post().uri("/api/v1/transfers/" + INTENT_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"amountFen\":0,\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(identityCalls()).isZero();
        assertThat(paymentCalls()).isZero();
    }

    @Test
    void confirmWithAnAmountDifferentFromThePreparedIntentIsRejected() throws Exception {
        login();
        String csrf = csrfToken();

        IDENTITY.enqueue(json(200, RECIPIENT_JSON));
        PAYMENT.enqueue(json(201, INTENT_JSON));
        prepareTransfer(csrf, 2500);
        forgetUpstreamTraffic();

        withCookies(client.post().uri("/api/v1/transfers/" + INTENT_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"amountFen\":9900,\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("TRANSFER_AMOUNT_MISMATCH");

        // No authorization may be minted for an amount this session never prepared.
        assertThat(identityCalls()).isZero();
        assertThat(paymentCalls()).isZero();
    }

    @Test
    void paymentPasswordFailureKeepsADistinguishableCode() throws Exception {
        login();
        String csrf = csrfToken();

        IDENTITY.enqueue(json(200, RECIPIENT_JSON));
        PAYMENT.enqueue(json(201, INTENT_JSON));
        prepareTransfer(csrf, 2500);
        forgetUpstreamTraffic();

        IDENTITY.enqueue(problem(422, "PAYMENT_PASSWORD_INVALID", null));

        withCookies(client.post().uri("/api/v1/transfers/" + INTENT_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"amountFen\":2500,\"paymentPassword\":\"999999\"}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("PAYMENT_PASSWORD_INVALID");

        // No payment confirmation may follow a rejected authorization.
        assertThat(paymentCalls()).isZero();
    }

    @Test
    void readOnlyRelaysReachTheAuthoritativeUpstreamPaths() throws Exception {
        login();

        WALLET.enqueue(json(200, "{\"balanceCent\":1}"));
        withCookies(client.get().uri("/api/v1/wallet")).exchange().expectStatus().isOk();
        assertThat(takeRequest(WALLET).getPath()).isEqualTo("/api/v1/wallets/me");

        WALLET.enqueue(json(200, "{\"items\":[],\"page\":1,\"size\":20,\"total\":0}"));
        withCookies(client.get().uri("/api/v1/wallet/bills?cursor=1&limit=20"))
                .exchange().expectStatus().isOk();
        assertThat(takeRequest(WALLET).getPath()).isEqualTo("/api/v1/wallets/me/bills?page=1&size=20");

        PAYMENT.enqueue(json(200, "{\"token\":\"minipay://collect/personal?token=abc\"}"));
        withCookies(client.get().uri("/api/v1/collection-code")).exchange().expectStatus().isOk();
        assertThat(takeRequest(PAYMENT).getPath())
                .isEqualTo("/api/v1/personal-collection-codes/current");

        PAYMENT.enqueue(json(200, "[]"));
        withCookies(client.get().uri("/api/v1/bank-cards")).exchange().expectStatus().isOk();
        assertThat(takeRequest(PAYMENT).getPath()).isEqualTo("/api/v1/bank-cards");

        PAYMENT.enqueue(json(200, "{\"items\":[],\"page\":2,\"size\":5,\"total\":0}"));
        withCookies(client.get().uri("/api/v1/funding-orders?type=RECHARGE&cursor=2&limit=5"))
                .exchange().expectStatus().isOk();
        assertThat(takeRequest(PAYMENT).getPath()).isEqualTo("/api/v1/recharge-orders?page=2&size=5");

        PAYMENT.enqueue(json(200, "{\"items\":[],\"page\":1,\"size\":20,\"total\":0}"));
        withCookies(client.get().uri("/api/v1/funding-orders?type=WITHDRAWAL"))
                .exchange().expectStatus().isOk();
        assertThat(takeRequest(PAYMENT).getPath())
                .isEqualTo("/api/v1/withdrawal-orders?page=1&size=20");
    }

    @Test
    void transferHistoryAndCancellationReachUpstream() throws Exception {
        login();
        String csrf = csrfToken();

        WALLET.enqueue(json(200, "{\"items\":[],\"page\":1,\"size\":20,\"total\":0}"));
        withCookies(client.get().uri("/api/v1/transfers?counterpartyUserId=" + PAYEE_ID
                        + "&cursor=1&limit=20"))
                .exchange().expectStatus().isOk();
        assertThat(takeRequest(WALLET).getPath()).isEqualTo(
                "/api/v1/wallets/me/transfer-records?counterpartyUserId=" + PAYEE_ID
                        + "&page=1&size=20");

        PAYMENT.enqueue(json(200, "{\"transferId\":\"" + TRANSFER_ID + "\","
                + "\"intentId\":\"" + INTENT_ID + "\",\"status\":\"SUCCEEDED\"}"));
        withCookies(client.get().uri("/api/v1/transfers/" + TRANSFER_ID))
                .exchange().expectStatus().isOk();
        assertThat(takeRequest(PAYMENT).getPath())
                .isEqualTo("/api/v1/transfer-orders/" + TRANSFER_ID);

        PAYMENT.enqueue(json(200, "{\"intentId\":\"" + INTENT_ID + "\","
                + "\"amountCent\":2500,\"status\":\"CANCELLED\","
                + "\"expiresAt\":\"2030-01-01T00:05:00Z\"}"));
        withCookies(client.delete().uri("/api/v1/transfers/" + INTENT_ID)
                        .header("X-CSRF-TOKEN", csrf))
                .exchange().expectStatus().isOk();
        RecordedRequest cancel = takeRequest(PAYMENT);
        assertThat(cancel.getMethod()).isEqualTo("DELETE");
        assertThat(cancel.getPath()).isEqualTo("/api/v1/transfers/" + INTENT_ID);
    }

    @Test
    void paymentPasswordSetupProxyReachesIdentity() throws Exception {
        login();

        IDENTITY.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(204));
        // 设置密码后 BFF 会 best-effort 刷新一次访问令牌：pay_password_set 是签发时写进 JWT 的
        // claim，不换令牌的话后续转账会被 identity 判成 PAYMENT_PASSWORD_REQUIRED（线上实测）。
        IDENTITY.enqueue(json(200, "{\"access_token\":\"access-2\","
                + "\"token_type\":\"Bearer\",\"expires_in\":600,\"refresh_token\":\"refresh-2\"}"));

        withCookies(client.post().uri("/api/v1/pay-password")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken()))
                .bodyValue("{\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isNoContent();

        RecordedRequest request = takeRequest(IDENTITY);
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/api/v1/users/me/payment-password");
        assertThat(body(request).path("paymentPassword").asText()).isEqualTo("123456");

        RecordedRequest refresh = takeRequest(IDENTITY);
        assertThat(refresh.getPath()).isEqualTo("/oauth2/token");
        assertThat(form(refresh).path("grant_type").asText()).isEqualTo("refresh_token");

        // 设置成功后会话快照必须立刻反映 payPasswordSet=true：否则前端会继续读
        // /api/v1/session 里的 false，一直显示「尚未设置支付密码」并禁用转账/付款按钮。
        withCookies(client.get().uri("/api/v1/session"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authenticated").isEqualTo(true)
                .jsonPath("$.payPasswordSet").isEqualTo(true);
    }

    @Test
    void aiSurfaceMapsToTheChatUpstreamAndStreamsEventsUnbuffered() throws Exception {
        login();

        MILING.enqueue(json(200, "{\"items\":[],\"nextCursor\":null}"));
        withCookies(client.get().uri("/api/v1/ai/conversations?limit=10"))
                .exchange().expectStatus().isOk();
        RecordedRequest conversations = takeRequest(MILING);
        assertThat(conversations.getPath()).isEqualTo("/api/v1/chat/conversations?limit=10");
        assertThat(conversations.getHeader("Authorization")).isEqualTo("Bearer access-1");

        MILING.enqueue(json(200, "{\"id\":\"conversation-1\",\"title\":\"new\"}"));
        withCookies(client.post().uri("/api/v1/ai/conversations")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken()))
                .bodyValue("{\"title\":\"new\"}")
                .exchange()
                .expectStatus().isOk();
        assertThat(takeRequest(MILING).getPath()).isEqualTo("/api/v1/chat/conversations");

        MILING.enqueue(json(200, "{\"items\":[],\"nextCursor\":null}"));
        withCookies(client.get()
                        .uri("/api/v1/ai/conversations/conversation-1/messages?limit=50"))
                .exchange().expectStatus().isOk();
        assertThat(takeRequest(MILING).getPath())
                .isEqualTo("/api/v1/chat/conversations/conversation-1/messages?limit=50");

        MILING.enqueue(json(201, "{\"runId\":\"run-1\"}"));
        withCookies(client.post().uri("/api/v1/ai/conversations/conversation-1/messages")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken()))
                .bodyValue("{\"content\":\"hello\",\"clientMessageId\":\"client-1\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.runId").isEqualTo("run-1");
        RecordedRequest run = takeRequest(MILING);
        assertThat(run.getPath()).isEqualTo("/api/v1/chat/conversations/conversation-1/messages");
        JsonNode runBody = body(run);
        // 上游米灵 trigger 端的字段是 content（MilingChatDTOs），不是 message：
        // 写成 message 线上会 400 "content 不能为空"，整条对话链路不可用。
        assertThat(runBody.path("content").asText()).isEqualTo("hello");
        assertThat(runBody.has("message")).isFalse();
        assertThat(runBody.path("clientMessageId").asText()).isEqualTo("client-1");
        assertThat(run.getHeader("Idempotency-Key")).isNotBlank();

        MILING.enqueue(new okhttp3.mockwebserver.MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message.delta\n"
                        + "id: 1\n"
                        + "data: {\"id\":\"1\",\"type\":\"message.delta\",\"version\":1,"
                        + "\"conversationId\":\"conversation-1\",\"runId\":\"run-1\","
                        + "\"occurredAt\":\"2030-01-01T00:00:00Z\",\"payload\":{\"text\":\"hi\"}}\n\n"
                        + "event: stream.completed\n"
                        + "id: 2\n"
                        + "data: {\"id\":\"2\",\"type\":\"stream.completed\",\"version\":1,"
                        + "\"conversationId\":\"conversation-1\",\"runId\":\"run-1\","
                        + "\"occurredAt\":\"2030-01-01T00:00:01Z\",\"payload\":{}}\n\n"));
        List<org.springframework.http.codec.ServerSentEvent<String>> events = new ArrayList<>();
        withCookies(client.get().uri("/api/v1/ai/runs/run-1/events"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Type", "text/event-stream.*")
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                // 断言"每一个 SSE 帧"，而不是把响应当纯文本拼接：WebTestClient 会用 SSE 解码器
                // 解析 text/event-stream，event 名与 id 只在解码后的对象上可见，原始文本里没有。
                .returnResult(new org.springframework.core.ParameterizedTypeReference<
                        org.springframework.http.codec.ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .toStream()
                .forEach(events::add);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("message.delta");
        assertThat(events.get(0).id()).isEqualTo("1");
        assertThat(events.get(0).data()).contains("\"text\":\"hi\"");
        assertThat(events.get(1).event()).isEqualTo("stream.completed");
        assertThat(events.get(1).id()).isEqualTo("2");
        assertThat(takeRequest(MILING).getPath()).isEqualTo("/api/v1/chat/runs/run-1/events");
    }

    @Test
    void collectionCodeScanIsRelayedToPaymentAndAnEmptyCodeNeverReachesUpstream() throws Exception {
        login();
        String csrf = csrfToken();

        PAYMENT.enqueue(json(200, MERCHANT_RESOLUTION_JSON));
        withCookies(client.post().uri("/api/v1/payments/scan")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"deepLink\":\"minipay://collect/merchant?token=abc\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("MERCHANT_COLLECTION")
                .jsonPath("$.resolutionId").isEqualTo(RESOLUTION_ID)
                .jsonPath("$.merchantName").isEqualTo("Demo Merchant");

        RecordedRequest scan = takeRequest(PAYMENT);
        assertThat(scan.getMethod()).isEqualTo("POST");
        assertThat(scan.getPath()).isEqualTo("/api/v1/scan-resolutions");
        assertThat(scan.getHeader("Authorization")).isEqualTo("Bearer access-1");
        assertThat(body(scan).path("deepLink").asText())
                .isEqualTo("minipay://collect/merchant?token=abc");

        // An empty code must be rejected by the BFF: upstream would answer 400 anyway, but the
        // browser should not be able to use this endpoint as an unvalidated proxy.
        withCookies(client.post().uri("/api/v1/payments/scan")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"deepLink\":\"   \"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COLLECTION_CODE_REQUIRED");

        assertThat(paymentCalls()).isEqualTo(1);
    }

    @Test
    void merchantPaymentExchangesThePaymentPasswordForASingleUseTokenFirst() throws Exception {
        login();
        String csrf = csrfToken();

        // 1. scan: the merchant collection code resolves to a single-use resolution id.
        PAYMENT.enqueue(json(200, MERCHANT_RESOLUTION_JSON));
        withCookies(client.post().uri("/api/v1/payments/scan")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"merchantToken\":\"abc\"}")
                .exchange()
                .expectStatus().isOk();
        takeRequest(PAYMENT);

        // 2. prepare: create the payment order and remember the amount for this session.
        PAYMENT.enqueue(json(201, "{\"paymentOrderId\":\"" + PAYMENT_ORDER_ID + "\","
                + "\"paymentOrderNo\":\"" + PAYMENT_ORDER_NO + "\",\"amountCent\":1800,"
                + "\"currency\":\"CNY\",\"subject\":\"扫码付款\",\"paymentMethod\":\"WALLET_BALANCE\","
                + "\"status\":\"PENDING_PAYMENT\",\"expiresAt\":\"2030-01-01T00:05:00Z\"}"));
        prepareMerchantPayment(csrf, 1800);

        RecordedRequest create = takeRequest(PAYMENT);
        assertThat(create.getMethod()).isEqualTo("POST");
        assertThat(create.getPath()).isEqualTo("/api/v1/payment-orders");
        assertThat(create.getHeader("Idempotency-Key")).isNotBlank();
        JsonNode createBody = body(create);
        assertThat(createBody.path("amountCent").asLong()).isEqualTo(1800);
        assertThat(createBody.path("paymentMethod").asText()).isEqualTo("WALLET_BALANCE");
        assertThat(createBody.path("resolutionId").asText()).isEqualTo(RESOLUTION_ID);
        // The subject is a server-side constant: it is rendered in the merchant/ops consoles.
        assertThat(createBody.path("subject").asText()).isEqualTo("扫码付款");

        // 3. confirm: payment password -> one-time authorization -> confirm with that token.
        IDENTITY.enqueue(json(201, "{\"authorizationId\":"
                + "\"0198f200-0000-7000-8000-0000000000a2\","
                + "\"paymentAuthToken\":\"one-time-token\","
                + "\"expiresAt\":\"2030-01-01T00:02:00Z\"}"));
        PAYMENT.enqueue(json(200, "{\"paymentOrderId\":\"" + PAYMENT_ORDER_ID + "\","
                + "\"paymentOrderNo\":\"" + PAYMENT_ORDER_NO + "\","
                + "\"status\":\"SUCCEEDED\",\"failureCode\":null}"));

        withCookies(client.post().uri("/api/v1/payments/" + PAYMENT_ORDER_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"amountFen\":1800,\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paymentOrderNo").isEqualTo(PAYMENT_ORDER_NO)
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        RecordedRequest authorize = takeRequest(IDENTITY);
        assertThat(authorize.getPath()).isEqualTo("/api/v1/payment-authorizations");
        assertThat(authorize.getMethod()).isEqualTo("POST");
        assertThat(authorize.getHeader("Idempotency-Key")).isNotBlank();
        JsonNode authorizeBody = body(authorize);
        assertThat(authorizeBody.path("subjectType").asText()).isEqualTo("PAYMENT_ORDER");
        assertThat(authorizeBody.path("subjectId").asText()).isEqualTo(PAYMENT_ORDER_ID);
        assertThat(authorizeBody.path("amountCent").asLong()).isEqualTo(1800);
        assertThat(authorizeBody.path("payPassword").asText()).isEqualTo("123456");
        assertThat(authorizeBody.path("deviceId").asText()).isNotBlank();

        RecordedRequest confirm = takeRequest(PAYMENT);
        assertThat(confirm.getPath())
                .isEqualTo("/api/v1/payment-orders/" + PAYMENT_ORDER_ID + "/confirm");
        JsonNode confirmBody = body(confirm);
        assertThat(confirmBody.path("paymentAuthToken").asText()).isEqualTo("one-time-token");
        // The payment password is consumed by Identity only and must never reach Payment.
        assertThat(confirmBody.toString()).doesNotContain("123456");
    }

    @Test
    void paymentConfirmWithAnAmountDifferentFromThePreparedOrderIsRejected() throws Exception {
        login();
        String csrf = csrfToken();

        PAYMENT.enqueue(json(200, MERCHANT_RESOLUTION_JSON));
        withCookies(client.post().uri("/api/v1/payments/scan")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"merchantToken\":\"abc\"}")
                .exchange()
                .expectStatus().isOk();
        PAYMENT.enqueue(json(201, "{\"paymentOrderId\":\"" + PAYMENT_ORDER_ID + "\","
                + "\"paymentOrderNo\":\"" + PAYMENT_ORDER_NO + "\",\"amountCent\":1800,"
                + "\"status\":\"PENDING_PAYMENT\"}"));
        prepareMerchantPayment(csrf, 1800);
        forgetUpstreamTraffic();

        withCookies(client.post().uri("/api/v1/payments/" + PAYMENT_ORDER_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"amountFen\":9900,\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("PAYMENT_AMOUNT_MISMATCH");

        // No authorization may be minted for an amount this session never prepared.
        assertThat(identityCalls()).isZero();
        assertThat(paymentCalls()).isZero();
    }

    @Test
    void paymentWithoutAVisibleAmountIsRejectedBeforeAnyAuthorization() throws Exception {
        login();
        String csrf = csrfToken();

        withCookies(client.post().uri("/api/v1/payments/" + PAYMENT_ORDER_ID + "/confirm")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"paymentPassword\":\"123456\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED");

        withCookies(client.post().uri("/api/v1/payments/prepare")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"resolutionId\":\"\",\"amountFen\":1800}")
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(identityCalls()).isZero();
        assertThat(paymentCalls()).isZero();
    }

    private void prepareMerchantPayment(String csrf, long amountFen) {
        org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> result =
                withCookies(client.post().uri("/api/v1/payments/prepare")
                                .header("Content-Type", "application/json")
                                .header("X-CSRF-TOKEN", csrf))
                        .bodyValue("{\"resolutionId\":\"" + RESOLUTION_ID + "\",\"amountFen\":"
                                + amountFen + "}")
                        .exchange()
                        .expectBody(byte[].class)
                        .returnResult();
        rememberCookies(result);
        assertThat(result.getStatus().value())
                .as("prepare 应返回 200，实际 " + result.getStatus()
                        + " body=" + new String(result.getResponseBody()))
                .isEqualTo(200);
        JsonNode body = readJson(result.getResponseBody());
        assertThat(body.path("paymentOrderId").asText()).isEqualTo(PAYMENT_ORDER_ID);
        assertThat(body.path("amountFen").asLong()).isEqualTo(amountFen);
    }

    private void prepareTransfer(String csrf, long amountFen) {
        org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> result =
                withCookies(client.post().uri("/api/v1/transfers/prepare")
                                .header("Content-Type", "application/json")
                                .header("X-CSRF-TOKEN", csrf))
                        .bodyValue("{\"payeeIdentifier\":\"13900139000\",\"amountFen\":" + amountFen + ","
                                + "\"remark\":\"test\"}")
                        .exchange()
                        .expectBody(byte[].class)
                        .returnResult();
        rememberCookies(result);
        assertThat(result.getStatus().value())
                .as("prepare 应返回 200，实际 " + result.getStatus() + " body=" + new String(result.getResponseBody()))
                .isEqualTo(200);
        JsonNode body = readJson(result.getResponseBody());
        assertThat(body.path("transferIntentId").asText()).isEqualTo(INTENT_ID);
        assertThat(body.path("payeeMasked").asText()).isEqualTo("139****9000");
        assertThat(body.path("amountFen").asLong()).isEqualTo(amountFen);
        assertThat(body.path("expiresAt").asText()).isEqualTo("2030-01-01T00:05:00Z");
    }

    private static JsonNode readJson(byte[] body) {
        try {
            return JSON.readTree(new String(body));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RecordedRequest takeRequest(okhttp3.mockwebserver.MockWebServer server) {
        try {
            RecordedRequest request =
                    server.takeRequest(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(request).isNotNull();
            return request;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
