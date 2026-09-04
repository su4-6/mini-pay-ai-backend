package com.minipay.identity.interfaces.rest;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 将本服务的会话 CSRF Token 以 JSON 暴露给独立部署的运营登录页。
 * 浏览器通过同域 /identity 反向代理访问，前端不持有服务端模板注入状态。
 */
@RestController
@RequestMapping("/api/v1")
public class CsrfController {

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(
                token.getHeaderName(),
                token.getParameterName(),
                token.getToken());
    }

    public record CsrfTokenResponse(String headerName, String parameterName, String token) {
    }
}
