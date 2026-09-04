package com.minipay.commerce.interfaces.rest;

import com.minipay.commerce.application.CommerceApplicationException;
import com.minipay.commerce.domain.model.CommerceDomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CommerceProblemHandler {
    @ExceptionHandler({CommerceApplicationException.class, CommerceDomainException.class})
    ProblemDetail business(RuntimeException exception, HttpServletRequest request) {
        String code = exception instanceof CommerceApplicationException application
                ? application.code() : ((CommerceDomainException) exception).code();
        HttpStatus status = code.endsWith("NOT_FOUND")
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return problem(status, code, exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ProblemDetail invalid(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "COMMERCE_REQUEST_INVALID",
                "请求参数无效", request);
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://docs.minipay.local/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getRequestId());
        return problem;
    }
}
