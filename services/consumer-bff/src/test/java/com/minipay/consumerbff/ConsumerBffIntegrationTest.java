package com.minipay.consumerbff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Boots the BFF against four MockWebServer upstreams and provides the deterministic login fixture
 * the H5 client uses: SMS send, PKCE code verify, token exchange, profile read.
 *
 * <p>A real embedded server is started ({@code RANDOM_PORT}) rather than a mock exchange, because
 * Spring Session commits sessions in a WebFilter: against a mock exchange nothing would ever be
 * stored, every request would look unauthenticated, and the tests would not exercise the production
 * session path at all.
 *
 * <p>Spring Session runs in memory for this slice: the application's
 * {@code ReactiveSessionRepository} is replaced so no Redis instance is required, while session id
 * rotation and cookie handling keep working exactly as they do in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConsumerBffIntegrationTest.InMemorySessionConfiguration.class)
abstract class ConsumerBffIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class InMemorySessionConfiguration {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.session.ReactiveSessionRepository<? extends org.springframework.session.Session>
                inMemorySessionRepository() {
            return new org.springframework.session.ReactiveMapSessionRepository(
                    new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final Duration TIMEOUT = Duration.ofSeconds(15);

    protected static final MockWebServer IDENTITY = new MockWebServer();
    protected static final MockWebServer WALLET = new MockWebServer();
    protected static final MockWebServer PAYMENT = new MockWebServer();
    protected static final MockWebServer MILING = new MockWebServer();

    protected static final String CONSUMER_ID = "0198f200-0000-7000-8000-0000000000c1";
    protected static final String DEVICE_ID = "0198f200-0000-7000-8000-0000000000d1";
    protected static final String MOBILE = "13800138000";

    @Autowired
    protected WebTestClient client;

    /**
     * WebTestClient keeps no cookie jar, so the cookie is threaded explicitly: without it the CSRF
     * token retrieved from one WebSession would be validated against a fresh session and every
     * state-changing call would 403.
     */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    private static final List<MockWebServer> SERVERS = List.of(IDENTITY, WALLET, PAYMENT, MILING);

    private int identityBaseline;
    private int walletBaseline;
    private int paymentBaseline;
    private int milingBaseline;

    @BeforeEach
    void resetCookieJarAndStubs() {
        cookies.clear();
        // MockWebServer's default QueueDispatcher keeps serving whatever earlier test methods left
        // queued, which silently shifts every later stub by one response. Replacing the dispatcher
        // is the supported way to empty that queue.
        for (MockWebServer server : SERVERS) {
            server.setDispatcher(new okhttp3.mockwebserver.QueueDispatcher());
            drain(server);
        }
        identityBaseline = IDENTITY.getRequestCount();
        walletBaseline = WALLET.getRequestCount();
        paymentBaseline = PAYMENT.getRequestCount();
        milingBaseline = MILING.getRequestCount();
    }

    /**
     * MockWebServer records every request on the instance and the instance is shared by the whole
     * test class: without draining, {@code takeRequest} in one test returns a request recorded by a
     * previous test and every path assertion compares against the wrong call.
     */
    private static void drain(MockWebServer server) {
        try {
            while (server.takeRequest(20, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
                // discard; only the queue position matters
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Upstream calls made during the current test method (the servers outlive the class).
     */
    protected int identityCalls() {
        return IDENTITY.getRequestCount() - identityBaseline;
    }

    /**
     * Forgets every upstream request recorded so far and re-baselines the counters.
     *
     * <p>The fixture login is setup, not the behaviour under test, so it is dropped at the end of
     * {@link #login()}: otherwise {@code takeRequest} would hand a test the login's own calls and
     * every "the upstream was not touched" assertion would count the four login calls.
     */
    protected void forgetUpstreamTraffic() {
        for (MockWebServer server : SERVERS) {
            drain(server);
        }
        identityBaseline = IDENTITY.getRequestCount();
        walletBaseline = WALLET.getRequestCount();
        paymentBaseline = PAYMENT.getRequestCount();
        milingBaseline = MILING.getRequestCount();
    }

    protected int walletCalls() {
        return WALLET.getRequestCount() - walletBaseline;
    }

    protected int paymentCalls() {
        return PAYMENT.getRequestCount() - paymentBaseline;
    }

    protected int milingCalls() {
        return MILING.getRequestCount() - milingBaseline;
    }

    /** Adds the accumulated Cookie header to a request spec. */
    protected WebTestClient.RequestBodySpec withCookies(WebTestClient.RequestBodySpec spec) {
        return cookies.isEmpty() ? spec : spec.header(HttpHeaders.COOKIE, cookieHeader());
    }

    protected WebTestClient.RequestHeadersSpec<?> withCookies(WebTestClient.RequestHeadersSpec<?> spec) {
        return cookies.isEmpty() ? spec : spec.header(HttpHeaders.COOKIE, cookieHeader());
    }

    private String cookieHeader() {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    /** Captures any session cookie the BFF set on the last response. */
    protected void rememberCookies(org.springframework.test.web.reactive.server.ExchangeResult result) {
        List<String> setCookies = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return;
        }
        for (String setCookie : setCookies) {
            rememberCookie(setCookie);
        }
    }

    /** Runs the spec, captures its session cookie, and returns the decoded body. */
    protected byte[] exchangeRememberingCookies(
            org.springframework.test.web.reactive.server.WebTestClient.BodyContentSpec spec) {
        org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> result =
                spec.returnResult();
        rememberCookies(result);
        return result.getResponseBody();
    }

    private void rememberCookie(String setCookie) {
        String[] parts = setCookie.split(";");
        int separator = parts[0].indexOf('=');
        if (separator <= 0) {
            return;
        }
        String name = parts[0].substring(0, separator).trim();
        String value = parts[0].substring(separator + 1).trim();
        boolean expired = false;
        for (String attribute : parts) {
            String normalized = attribute.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("max-age=0")
                    || normalized.startsWith("expires=thu, 01 jan 1970")) {
                expired = true;
            }
        }
        if (value.isEmpty() || expired) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

    /** Queues the SMS challenge issuance of an unauthenticated login. */
    protected static void enqueueLogin() {
        IDENTITY.enqueue(json(202, "{\"challengeId\":\"challenge-1\","
                + "\"maskedMobile\":\"138****8000\","
                + "\"expiresAt\":\"2030-01-01T00:05:00Z\","
                + "\"resendAfterSeconds\":60}"));
    }

    /** Queues the verification, token exchange and profile read used by {@link #login()}. */
    protected static void enqueueVerification() {
        IDENTITY.enqueue(json(200, "{\"authorizationCode\":\"auth-code-1\","
                + "\"expiresAt\":\"2030-01-01T00:01:00Z\","
                + "\"userId\":\"" + CONSUMER_ID + "\","
                + "\"payPasswordSet\":true,"
                + "\"onboardingRequired\":false,"
                + "\"realNameStatus\":\"VERIFIED\","
                + "\"realNameVerified\":true,"
                + "\"phone\":\"" + MOBILE + "\","
                + "\"merchantPasswordConfigured\":false}"));
        IDENTITY.enqueue(json(200, "{\"access_token\":\"access-1\","
                + "\"token_type\":\"Bearer\",\"expires_in\":600,"
                + "\"refresh_token\":\"refresh-1\",\"scope\":\"wallet.read\"}"));
        IDENTITY.enqueue(json(200, "{\"userId\":\"" + CONSUMER_ID + "\",\"nickname\":\"米灵用户\","
                + "\"miniPayNo\":\"80000001\"}"));
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) throws IOException {
        startServers();
        registry.add("minipay.consumer-bff.identity-url", () -> url(IDENTITY));
        registry.add("minipay.consumer-bff.wallet-url", () -> url(WALLET));
        registry.add("minipay.consumer-bff.payment-url", () -> url(PAYMENT));
        registry.add("minipay.consumer-bff.miling-url", () -> url(MILING));
    }

    private static synchronized void startServers() throws IOException {
        for (MockWebServer server : new MockWebServer[] {IDENTITY, WALLET, PAYMENT, MILING}) {
            if (server.getPort() == -1) {
                server.start();
            }
        }
    }

    /**
     * Loopback literal rather than {@code localhost}: on a dual-stack Windows host {@code localhost}
     * can resolve to IPv6 while MockWebServer listens on IPv4, which shows up as an unrelated
     * upstream 502 on whichever client loses the race.
     */
    private static String url(MockWebServer server) {
        return "http://127.0.0.1:" + server.getPort();
    }

    protected static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    protected static MockResponse problem(int status, String code, String detail) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("{\"type\":\"https://docs.minipay.local/problems/"
                        + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                        + "\",\"title\":\"" + code + "\",\"status\":" + status
                        + ",\"code\":\"" + code + "\""
                        + (detail == null ? "" : ",\"detail\":\"" + detail + "\"") + "}");
    }

    protected WebTestClient loggedInClient() {
        login();
        return client;
    }

    /**
     * Performs the two-step login the H5 uses: request the SMS code (which stores the server-side
     * PKCE verifier), then verify it and complete the token exchange.
     */
    protected void login() {
        String csrf = csrfToken();
        enqueueLogin();
        exchangeRememberingCookies(withCookies(client.post().uri("/api/v1/session/sms")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"mobile\":\"" + MOBILE + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.challengeId").isEqualTo("challenge-1"));

        enqueueVerification();
        exchangeRememberingCookies(withCookies(client.post().uri("/api/v1/session")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf))
                .bodyValue("{\"mobile\":\"" + MOBILE + "\",\"challengeId\":\"challenge-1\","
                        + "\"code\":\"123456\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(CONSUMER_ID));
        forgetUpstreamTraffic();
    }

    /** Current session cookie value, used to prove the identifier rotates on login. */
    protected String currentSessionId() {
        String value = cookies.get("__Host-minipay-consumer");
        return value == null ? "" : value;
    }

    /**
     * The CSRF handshake the H5 performs before its first state-changing call.
     *
     * <p>必须带上已累积的会话 Cookie：`/api/v1/csrf` 会把令牌存进 WebSession，因此**不带 Cookie 的
     * 调用会新建一个空会话**，随后 {@code rememberCookies} 把那个空会话的 Cookie 覆盖进 cookie jar，
     * 登录得到的有令牌会话就被顶掉了 —— 表现是"登录明明成功，后续写请求却 401"。
     */
    protected String csrfToken() {
        org.springframework.test.web.reactive.server.EntityExchangeResult<byte[]> result =
                withCookies(client.get().uri("/api/v1/csrf"))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(byte[].class)
                        .returnResult();
        rememberCookies(result);
        try {
            return JSON.readTree(new String(result.getResponseBody())).path("token").asText();
        } catch (IOException exception) {
            throw new IllegalStateException("CSRF token was not readable", exception);
        }
    }

    /**
     * Decoded JSON body of a recorded request.
     *
     * <p>必须 clone：{@code RecordedRequest.getBody()} 返回的是同一个 {@code Buffer}，第一次
     * {@code readUtf8()} 就把它读空，所以同一个请求读第二次会得到空串（表现为"字段明明发了却是
     * 空值"的假失败）。
     */
    protected static JsonNode body(RecordedRequest request) throws IOException {
        String value = request.getBody().clone().readUtf8();
        return value.isBlank() ? JSON.createObjectNode() : JSON.readTree(value);
    }

    protected static JsonNode form(RecordedRequest request) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
        for (String pair : request.getBody().clone().readUtf8().split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                node.put(
                        java.net.URLDecoder.decode(pair.substring(0, separator),
                                java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(separator + 1),
                                java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return node;
    }
}
