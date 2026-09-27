package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Read-only consumer relays. Each path maps to the authoritative upstream resource and the response
 * document is relayed verbatim so the H5 sees the real wallet and payment field names.
 */
@RestController
@RequestMapping("/api/v1")
public class ConsumerProxyController {

    private final ConsumerSessionService sessions;

    public ConsumerProxyController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/wallet")
    public Mono<ResponseEntity<String>> wallet(WebSession session, ServerWebExchange exchange) {
        return sessions.proxy(
                        session, exchange, HttpMethod.GET, "/api/v1/wallets/me", Map.of(), null, null)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/wallet/bills")
    public Mono<ResponseEntity<String>> bills(
            WebSession session,
            ServerWebExchange exchange,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return sessions.proxy(
                        session,
                        exchange,
                        HttpMethod.GET,
                        "/api/v1/wallets/me/bills",
                        pageQuery(cursor, limit, "20"),
                        null,
                        null)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/collection-code")
    public Mono<ResponseEntity<String>> collectionCode(
            WebSession session, ServerWebExchange exchange) {
        return sessions.proxy(
                        session,
                        exchange,
                        HttpMethod.GET,
                        "/api/v1/personal-collection-codes/current",
                        Map.of(),
                        null,
                        null)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/bank-cards")
    public Mono<ResponseEntity<String>> bankCards(WebSession session, ServerWebExchange exchange) {
        return sessions.proxy(
                        session, exchange, HttpMethod.GET, "/api/v1/bank-cards", Map.of(), null, null)
                .map(UpstreamResponses::toEntity);
    }

    static Map<String, String> pageQuery(String cursor, String limit, String defaultSize) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", cursor == null || cursor.isBlank() ? "1" : cursor);
        query.put("size", limit == null || limit.isBlank() ? defaultSize : limit);
        return query;
    }
}
