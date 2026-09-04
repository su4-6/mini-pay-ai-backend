package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.PaymentProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PaymentProblemHandler {
    @ExceptionHandler(PaymentProblemException.class)
    ResponseEntity<Map<String, Object>> paymentProblem(
            PaymentProblemException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(
                        exception.code(),
                        exception.status().value(),
                        request.getRequestURI()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> badRequest(
            Exception exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem("INVALID_REQUEST", 400, request.getRequestURI()));
    }

    private Map<String, Object> problem(String code, int status, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create("https://minipay.local/problems/" + code.toLowerCase()));
        body.put("title", code);
        body.put("status", status);
        body.put("code", code);
        body.put("instance", instance);
        body.put("timestamp", Instant.now());
        return body;
    }
}
