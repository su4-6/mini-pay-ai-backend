package com.minipay.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint =
            new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerDeniedHandler =
            new BearerTokenAccessDeniedHandler();

    public ApiSecurityProblemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception) throws IOException {
        bearerEntryPoint.commence(request, response, exception);
        write(request, response, 401, "INVALID_ACCESS_TOKEN", "Authentication required");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        if (!(exception instanceof CsrfException)) {
            bearerDeniedHandler.handle(request, response, exception);
        }
        String code = exception instanceof CsrfException ? "CSRF_TOKEN_INVALID" : "INSUFFICIENT_SCOPE";
        write(request, response, 403, code, "Access denied");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", URI.create(
                "https://docs.minipay.local/problems/" + code.toLowerCase().replace('_', '-')));
        problem.put("title", status == 401 ? "Unauthorized" : "Forbidden");
        problem.put("status", status);
        problem.put("code", code);
        problem.put("requestId", RequestIdFilter.get(request));
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
