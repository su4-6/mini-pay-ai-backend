package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.AgentApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AgentProblemAdvice {
    @ExceptionHandler(AgentApplicationException.class)
    ProblemDetail handleAgentProblem(AgentApplicationException exception, HttpServletRequest request) {
        HttpStatus status = statusFor(exception.code());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setType(URI.create("https://docs.minipay.local/problems/"
                + exception.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", exception.code());
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "请求字段格式不正确");
        problem.setType(URI.create("https://docs.minipay.local/problems/request-validation-failed"));
        problem.setTitle("Bad Request");
        problem.setProperty("code", "REQUEST_VALIDATION_FAILED");
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    private static HttpStatus statusFor(String code) {
        if (code.equals("AGENT_CONCURRENCY_LIMIT_REACHED")) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.contains("CONFLICT") || code.contains("REUSED")) {
            return HttpStatus.CONFLICT;
        }
        if (code.contains("CURSOR") || code.contains("PAGE_SIZE") || code.contains("IDEMPOTENCY_KEY")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    private static String requestId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-ID");
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }
}
