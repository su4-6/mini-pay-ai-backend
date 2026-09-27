package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.error.SessionRequiredException;
import com.minipay.consumerbff.application.error.UpstreamProblemException;
import com.minipay.consumerbff.infrastructure.security.RequestIdWebFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * RFC 9457 problem details with a stable {@code code} and the propagated {@code requestId}.
 *
 * <p>Details are limited to upstream-authored problem text. Request bodies are never echoed, so a
 * validation failure on a payment password can never write the submitted value into the response.
 */
@RestControllerAdvice
public class ConsumerProblemAdvice {

    @ExceptionHandler(UpstreamProblemException.class)
    public ResponseEntity<Map<String, Object>> upstream(
            UpstreamProblemException exception, ServerWebExchange exchange) {
        return problem(exchange, exception.status(), exception.code(), exception.detail());
    }

    @ExceptionHandler(SessionRequiredException.class)
    public ResponseEntity<Map<String, Object>> sessionRequired(
            SessionRequiredException exception, ServerWebExchange exchange) {
        return problem(
                exchange,
                HttpStatus.UNAUTHORIZED,
                exception.getMessage() == null ? "SESSION_EXPIRED" : exception.getMessage(),
                "登录状态已失效，请重新登录");
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> invalidInput(
            ServerWebInputException exception, ServerWebExchange exchange) {
        // The framework exception message can embed the offending value, so only a reason is used.
        String reason = exception.getReason();
        return problem(
                exchange,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                reason == null ? "请求参数不合法" : reason);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(
            ResponseStatusException exception, ServerWebExchange exchange) {
        String code = exception.getReason() == null ? "REQUEST_REJECTED" : exception.getReason();
        return problem(
                exchange,
                HttpStatus.valueOf(exception.getStatusCode().value()),
                code,
                null);
    }

    private ResponseEntity<Map<String, Object>> problem(
            ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://docs.minipay.local/problems/"
                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("requestId", RequestIdWebFilter.get(exchange));
        body.put("instance", exchange.getRequest().getPath().value());
        if (detail != null && !detail.isBlank()) {
            body.put("detail", detail);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
