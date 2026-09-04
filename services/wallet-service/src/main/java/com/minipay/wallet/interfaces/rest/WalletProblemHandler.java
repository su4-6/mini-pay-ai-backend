package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WalletProblemHandler {
    @ExceptionHandler(WalletProblemException.class)
    ResponseEntity<ProblemDetail> wallet(
            WalletProblemException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> validation(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", request);
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(
                "https://docs.minipay.local/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        String requestId = request.getHeader("X-Request-Id");
        problem.setProperty("requestId",
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
