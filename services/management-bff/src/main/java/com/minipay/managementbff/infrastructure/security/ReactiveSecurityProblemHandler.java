package com.minipay.managementbff.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
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

    public ReactiveSecurityProblemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        return write(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException exception) {
        String code = exception instanceof CsrfException ? "CSRF_TOKEN_INVALID" : "ACCESS_DENIED";
        return write(exchange, HttpStatus.FORBIDDEN, code);
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://docs.minipay.local/problems/" + code.toLowerCase().replace('_', '-'));
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("code", code);
        problem.put("requestId", RequestIdWebFilter.get(exchange));
        problem.put("instance", exchange.getRequest().getPath().value());
        try {
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap(objectMapper.writeValueAsBytes(problem));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException exception) {
            return exchange.getResponse().setComplete();
        }
    }
}
