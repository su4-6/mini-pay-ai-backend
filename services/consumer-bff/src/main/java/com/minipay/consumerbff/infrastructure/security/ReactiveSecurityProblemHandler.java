package com.minipay.consumerbff.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.csrf.CsrfException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ReactiveSecurityProblemHandler
        implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {
    private final ObjectMapper objectMapper;
    public ReactiveSecurityProblemHandler(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        return write(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
    }
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException exception) {
        return write(exchange, HttpStatus.FORBIDDEN,
                exception instanceof CsrfException ? "CSRF_TOKEN_INVALID" : "ACCESS_DENIED");
    }
    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://docs.minipay.local/problems/" + code.toLowerCase().replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("requestId", RequestIdWebFilter.get(exchange));
        body.put("instance", exchange.getRequest().getPath().value());
        try {
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap(objectMapper.writeValueAsBytes(body));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception exception) {
            return exchange.getResponse().setComplete();
        }
    }
}
