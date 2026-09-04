package com.minipay.managementbff.interfaces.rest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Preserves trusted upstream status codes instead of converting expected login errors to HTTP 500. */
@RestControllerAdvice
public class UpstreamProblemHandler {
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<String> upstreamResponse(WebClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        return ResponseEntity.status(exception.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null || body.isBlank() ? "{\"code\":\"UPSTREAM_REQUEST_REJECTED\"}" : body);
    }

    @ExceptionHandler(WebClientRequestException.class)
    public ResponseEntity<String> upstreamUnavailable(WebClientRequestException exception) {
        return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"UPSTREAM_SERVICE_UNAVAILABLE\"}");
    }
}
