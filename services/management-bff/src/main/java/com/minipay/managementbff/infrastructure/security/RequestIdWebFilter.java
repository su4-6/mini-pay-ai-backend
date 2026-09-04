package com.minipay.managementbff.infrastructure.security;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {
    public static final String ATTRIBUTE = RequestIdWebFilter.class.getName() + ".requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = normalize(exchange.getRequest().getHeaders().getFirst(HEADER));
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange);
    }

    public static String get(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof String requestId ? requestId : normalize(
                exchange.getRequest().getHeaders().getFirst(HEADER));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String trimmed = value.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }
}
