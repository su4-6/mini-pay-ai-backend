package com.minipay.consumerbff.application.port;

import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbound port over Identity, Wallet, Payment and the Miling AI service. Implementations attach
 * {@code Authorization} and {@code X-Request-Id}, and retry exactly once after refreshing the
 * access token when an upstream answers 401.
 *
 * <p>Calls that need the WebSession are declared with an explicit {@code WebSession} parameter so
 * the session stays attached to the caller's Reactor context; passing the session as a value into
 * deeper operators is not supported by Spring Session.
 */
public interface ConsumerApiGateway {

    Mono<UpstreamResponse> call(
            WebSession session,
            ServerHttpRequest inbound,
            HttpMethod method,
            String path,
            Map<String, String> query,
            Object body,
            String idempotencyKey);

    /** Same-user read-only relay that forwards the exact incoming path, query and body. */
    Mono<UpstreamResponse> relay(
            WebSession session, ServerHttpRequest inbound, HttpMethod method, String targetPrefix);

    /**
     * Server-sent-event relay for AI runs. Events are returned decoded so the caller re-encodes them
     * with identical event names, ids, comments and payloads; there is no buffering and no
     * aggregation of partial model output.
     */
    Flux<ServerSentEvent<String>> events(
            WebSession session, ServerHttpRequest inbound, String path, MediaType accept);
}
