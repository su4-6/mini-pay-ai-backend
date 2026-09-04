package com.minipay.consumerbff.infrastructure.security;

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
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String value = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        String requestId = value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
        if (requestId.length() > 128) requestId = requestId.substring(0, 128);
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        return chain.filter(exchange);
    }
    public static String get(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof String requestId ? requestId : UUID.randomUUID().toString();
    }
}
