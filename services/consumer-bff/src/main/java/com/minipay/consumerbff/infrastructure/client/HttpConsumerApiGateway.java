package com.minipay.consumerbff.infrastructure.client;

import com.minipay.consumerbff.application.error.SessionRequiredException;
import com.minipay.consumerbff.application.port.ConsumerApiGateway;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway;
import com.minipay.consumerbff.application.port.SessionTokenStore;
import com.minipay.consumerbff.application.port.UpstreamResponse;
import com.minipay.consumerbff.domain.session.ConsumerTokens;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP implementation of the token relay.
 *
 * <p>Every call carries {@code Authorization: Bearer <accessToken>} and the propagated
 * {@code X-Request-Id}. A 401 triggers exactly one token refresh and one retry; if the retry is
 * rejected again the server-side session is cleared and the browser is told to sign in again. The
 * refresh is derived lazily with {@code switchIfEmpty} so a retry really performs a second refresh.
 */
public class HttpConsumerApiGateway implements ConsumerApiGateway {

    private static final ConcurrentMap<String, Mono<Void>> REFRESHES = new ConcurrentHashMap<>();

    private final WebClient identity;
    private final WebClient wallet;
    private final WebClient payment;
    private final WebClient miling;
    private final WebClient streamingMiling;
    private final IdentityAuthorizationGateway identityGateway;

    public HttpConsumerApiGateway(
            WebClient identity,
            WebClient wallet,
            WebClient payment,
            WebClient miling,
            WebClient streamingMiling,
            IdentityAuthorizationGateway identityGateway) {
        this.identity = identity;
        this.wallet = wallet;
        this.payment = payment;
        this.miling = miling;
        this.streamingMiling = streamingMiling;
        this.identityGateway = identityGateway;
    }

    /** WebFlux has no request scope, so the store is built from the caller's WebSession. */
    private static SessionTokenStore sessionStore(WebSession session) {
        return new com.minipay.consumerbff.infrastructure.session.WebSessionTokenStore(
                Mono.just(session));
    }

    @Override
    public Mono<UpstreamResponse> call(
            WebSession session,
            ServerHttpRequest inbound,
            HttpMethod method,
            String path,
            Map<String, String> query,
            Object body,
            String idempotencyKey) {
        SessionTokenStore sessions = sessionStore(session);
        String requestId = requestIdOf(inbound);
        String uri = buildUri(path, query);
        WebClient client = clientFor(path);
        return exchangeWithToken(
                        sessions, client, method, uri, body, idempotencyKey, requestId, true)
                .flatMap(exchanged -> {
                    if (!exchanged.unauthorized()) {
                        return Mono.just(toUpstreamResponse(exchanged));
                    }
                    return refreshAccessToken(session, sessions, exchanged.accessTokenUsed(), requestId)
                            .then(exchangeWithToken(
                                    sessions, client, method, uri, body, idempotencyKey,
                                    requestId, false))
                            .flatMap(retried -> {
                                if (!retried.unauthorized()) {
                                    return Mono.just(toUpstreamResponse(retried));
                                }
                                return sessions.invalidate()
                                        .then(Mono.error(SessionRequiredException.expired()));
                            });
                })
                .onErrorMap(org.springframework.web.reactive.function.client
                                .WebClientRequestException.class,
                        HttpConsumerApiGateway::translateRequestFailure);
    }

    @Override
    public Mono<UpstreamResponse> relay(
            WebSession session,
            ServerHttpRequest inbound,
            HttpMethod method,
            String targetPrefix) {
        String path = targetPrefix == null
                ? inbound.getURI().getRawPath()
                : targetPrefix + inbound.getURI().getRawPath().substring("/api/v1".length());
        return call(session, inbound, method, path, Map.of(), null, null);
    }

    @Override
    public Mono<UpstreamResponse> multipart(
            WebSession session,
            ServerHttpRequest inbound,
            String path,
            MediaType contentType,
            Flux<DataBuffer> body,
            String idempotencyKey) {
        SessionTokenStore sessions = sessionStore(session);
        String requestId = requestIdOf(inbound);
        WebClient client = clientFor(path);
        // The request body is a one-shot stream so a face image is never aggregated or written by
        // the BFF. A 401 therefore invalidates the session instead of replaying sensitive bytes.
        return exchangeMultipartWithToken(
                        sessions, client, path, contentType, body, idempotencyKey, requestId)
                .flatMap(exchanged -> exchanged.unauthorized()
                        ? sessions.invalidate().then(Mono.error(SessionRequiredException.expired()))
                        : Mono.just(toUpstreamResponse(exchanged)))
                .onErrorMap(org.springframework.web.reactive.function.client
                                .WebClientRequestException.class,
                        HttpConsumerApiGateway::translateRequestFailure);
    }

    @Override
    public Flux<ServerSentEvent<String>> events(
            WebSession session,
            ServerHttpRequest inbound,
            String path,
            MediaType accept) {
        String requestId = requestIdOf(inbound);
        String lastEventId = inbound.getHeaders().getFirst("Last-Event-ID");
        SessionTokenStore sessions = sessionStore(session);
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return sessions.loadTokens()
                .filter(ConsumerTokens::hasAccessToken)
                .switchIfEmpty(Mono.error(SessionRequiredException.expired()))
                .flatMapMany(tokens -> streamingMiling.get()
                        .uri(uriBuilder -> uriBuilder.path(relative).build())
                        .headers(headers -> {
                            headers.setBearerAuth(tokens.accessToken());
                            headers.set("X-Request-Id", requestId);
                            headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                            if (lastEventId != null && !lastEventId.isBlank()) {
                                headers.set("Last-Event-ID", lastEventId);
                            }
                        })
                        .retrieve()
                        .onStatus(status -> status.value() == 401, response -> {
                            response.releaseBody();
                            return sessions.invalidate()
                                    .then(Mono.error(SessionRequiredException.expired()));
                        })
                        .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                        })
                        // Decoded text is detached from the pooled network buffer, so the controller
                        // can write each event straight through without copy or re-buffering.
                        .publishOn(Schedulers.parallel(), 1));
    }

    private Mono<UpstreamExchange> exchangeWithToken(
            SessionTokenStore sessions,
            WebClient client,
            HttpMethod method,
            String uri,
            Object body,
            String idempotencyKey,
            String requestId,
            boolean required) {
        // loadTokens() always emits, so an explicit filter is what makes this fail closed: without
        // it an unauthenticated session would still be relayed upstream, only without a bearer
        // token, and the BFF edge would depend on the upstream to reject it.
        Mono<ConsumerTokens> tokens = required
                ? sessions.loadTokens()
                        .filter(ConsumerTokens::hasAccessToken)
                        .switchIfEmpty(Mono.error(SessionRequiredException.expired()))
                : sessions.loadTokens();
        return tokens
                .flatMap(current -> client.method(method)
                        .uri(uri)
                        .headers(headers -> {
                            if (current.hasAccessToken()) {
                                headers.setBearerAuth(current.accessToken());
                            }
                            headers.set("X-Request-Id", requestId);
                            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                                headers.set("Idempotency-Key", idempotencyKey);
                            }
                        })
                        .body(body == null ? Mono.empty() : Mono.just(body), Object.class)
                        // 必须在 exchangeToMono 内部读完响应体：一旦把 ClientResponse 透出这个
                        // lambda，Netty 已经把缓冲区归还连接池，之后再 bodyToMono(String) 只会
                        // 得到空 body（实测 null），表现为"上游 200 但 BFF 转发出空 JSON 或 502"。
                        .exchangeToMono(response -> readExchange(response, current.accessToken())));
    }

    private Mono<UpstreamExchange> exchangeMultipartWithToken(
            SessionTokenStore sessions,
            WebClient client,
            String uri,
            MediaType contentType,
            Flux<DataBuffer> body,
            String idempotencyKey,
            String requestId) {
        Mono<ConsumerTokens> tokens = sessions.loadTokens()
                .filter(ConsumerTokens::hasAccessToken)
                .switchIfEmpty(Mono.error(SessionRequiredException.expired()));
        return tokens.flatMap(current -> client.post()
                .uri(uri)
                .headers(headers -> {
                    if (current.hasAccessToken()) {
                        headers.setBearerAuth(current.accessToken());
                    }
                    headers.set("X-Request-Id", requestId);
                    headers.set("Idempotency-Key", idempotencyKey);
                    headers.setContentType(contentType);
                })
                .body(BodyInserters.fromDataBuffers(body))
                .exchangeToMono(response -> readExchange(response, current.accessToken())));
    }

    /** Status, content type and body of one upstream exchange, captured while the body is readable. */
    private record UpstreamExchange(int status, String contentType, String body, String accessTokenUsed) {

        boolean unauthorized() {
            return status == 401;
        }
    }

    private static Mono<UpstreamExchange> readExchange(ClientResponse response, String accessTokenUsed) {
        String contentType = response.headers().contentType()
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_JSON_VALUE);
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new UpstreamExchange(
                        response.statusCode().value(), contentType, body, accessTokenUsed));
    }

    private Mono<Void> refreshAccessToken(
            WebSession webSession,
            SessionTokenStore sessions,
            String rejectedAccessToken,
            String requestId) {
        String key = webSession.getId();
        Mono<Void> refresh = REFRESHES.computeIfAbsent(key, ignored -> Mono.defer(() ->
                        sessions.loadTokens()
                                .filter(ConsumerTokens::hasRefreshToken)
                                .switchIfEmpty(Mono.error(SessionRequiredException.expired()))
                                // Another request may already have rotated this session. If so, the
                                // caller only needs to retry with the newly stored access token.
                                .flatMap(tokens -> !tokens.accessToken().equals(rejectedAccessToken)
                                        ? Mono.empty()
                                        : identityGateway.refresh(tokens.refreshToken(), requestId)
                                                .flatMap(refreshed -> sessions.storeTokens(
                                                        new ConsumerTokens(
                                                                refreshed.accessToken(),
                                                                refreshed.refreshToken())))))
                .cache());
        return refresh.doFinally(ignored -> REFRESHES.remove(key, refresh));
    }

    private WebClient clientFor(String path) {
        if (path.startsWith("/api/v1/wallets")) {
            return wallet;
        }
        if (path.startsWith("/api/v1/chat")) {
            return miling;
        }
        // Identity 拥有的路径（按各服务 Controller 的 @RequestMapping 归属核对）：
        //   /oauth2/** 换令牌、/api/v1/auth/** 登录、/api/v1/users/** 资料与实名与二维码、
        //   /api/v1/transfer-recipients/** 收款人解析（转账 prepare 第一步）、
        //   /api/v1/payment-authorizations 支付密码换一次性授权令牌（confirm 第一步）、
        //   /api/v1/friends** 好友。
        // 漏掉其中任何一条都会落到下面的默认分支被打到 payment-service → 404 → 502，
        // 症状是"转账 / 付款确认整条链路坏掉"。
        if (path.startsWith("/oauth2/")
                || path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/users")
                || path.startsWith("/api/v1/real-name-verifications")
                || path.startsWith("/api/v1/transfer-recipients")
                || path.startsWith("/api/v1/payment-authorizations")
                || path.startsWith("/api/v1/friends")
                || path.startsWith("/api/v1/friend-requests")) {
            return identity;
        }
        return payment;
    }

    /**
     * 返回**带前导斜杠的路径字符串**（含查询串），交给 {@code WebClient.uri(String)}。
     *
     * <p>⚠️ 必须用 String 而不是 URI：`WebClient.uri(URI)` 收到相对 URI 时不会按 base URL 解析，
     * 而会把首段当成主机名（实测报 `Failed to resolve 'api'` 的 DNS 错误 → 502）。
     * 传 String 时 Spring 会用 base URL 作为根来解析，主机/端口得以保留。
     */
    private static String buildUri(String path, Map<String, String> query) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        StringBuilder builder = new StringBuilder(normalized);
        if (query != null && !query.isEmpty()) {
            // 参数按名字排序：调用方几乎都用 Map.of，其迭代顺序在不同 JVM 上不稳定，
            // 会让同一次调用的 URL 变成 `?size=20&page=1` 或 `?page=1&size=20`，
            // 上游日志、缓存键与测试断言都因此偶发不一致。
            StringBuilder parameters = new StringBuilder();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(query).entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                if (parameters.length() > 0) {
                    parameters.append('&');
                }
                parameters.append(entry.getKey()).append('=').append(entry.getValue());
            }
            if (parameters.length() > 0) {
                builder.append('?').append(parameters);
            }
        }
        return builder.toString();
    }

    private static UpstreamResponse toUpstreamResponse(UpstreamExchange exchange) {
        return new UpstreamResponse(
                exchange.status(), exchange.contentType(), exchange.body(), Instant.now());
    }

    private static Throwable translateRequestFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof com.minipay.consumerbff.application.error
                    .UpstreamProblemException problem) {
                return problem;
            }
            current = current.getCause();
        }
        return new com.minipay.consumerbff.application.error.UpstreamProblemException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "UPSTREAM_SERVICE_UNAVAILABLE",
                null);
    }

    static String requestIdOf(ServerHttpRequest request) {
        Object value = request.getAttributes()
                .get(com.minipay.consumerbff.infrastructure.security.RequestIdWebFilter.ATTRIBUTE);
        if (value instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String header = request.getHeaders().getFirst("X-Request-Id");
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}
