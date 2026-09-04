package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.AdminActionAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = SystemAdminController.class)
public class SystemAdminProblemHandler {
    private final AdminActionAuditService audits;

    public SystemAdminProblemHandler(AdminActionAuditService audits) {
        this.audits = audits;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception exception, HttpServletRequest request) {
        HttpStatus status = status(exception);
        String code = code(exception);
        String requestId = value(request.getHeader("X-Request-Id"), "unknown");
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            audits.record(actor(request), request.getMethod() + " " + request.getRequestURI(),
                    "ADMIN_REQUEST", request.getRequestURI(), "FAILED", code, requestId,
                    request.getHeader("X-Admin-Client-IP"), request.getHeader("User-Agent"));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, code);
        problem.setType(URI.create("urn:minipay:problem:" + code.toLowerCase()));
        problem.setTitle("Administrator request failed");
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId);
        return ResponseEntity.status(status).body(problem);
    }

    private static HttpStatus status(Exception exception) {
        if (exception instanceof ResponseStatusException response) {
            return HttpStatus.valueOf(response.getStatusCode().value());
        }
        if (exception instanceof MethodArgumentNotValidException
                || exception instanceof MissingRequestHeaderException
                || exception instanceof jakarta.validation.ConstraintViolationException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String code(Exception exception) {
        if (exception instanceof ResponseStatusException response
                && response.getReason() != null) return response.getReason();
        if (exception instanceof MethodArgumentNotValidException
                || exception instanceof MissingRequestHeaderException
                || exception instanceof jakarta.validation.ConstraintViolationException) {
            return "ADMIN_REQUEST_INVALID";
        }
        return "ADMIN_INTERNAL_ERROR";
    }

    private static UUID actor(HttpServletRequest request) {
        if (request.getUserPrincipal() instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getName());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String value(String input, String fallback) {
        return input == null || input.isBlank() ? fallback : input;
    }
}
