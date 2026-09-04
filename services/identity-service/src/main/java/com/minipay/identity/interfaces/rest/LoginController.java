package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.AdminAuthenticationService;
import com.minipay.identity.application.service.AdminAuthenticationService.RequestMetadata;
import com.minipay.identity.application.service.AuthRateLimitService;
import com.minipay.identity.application.service.CaptchaService;
import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.application.service.SmsChallengeService;
import com.minipay.identity.domain.model.AdminPrincipal;
import com.minipay.identity.infrastructure.persistence.LoginAuditRepository;
import com.minipay.identity.infrastructure.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class LoginController {
    private final AdminAuthenticationService authentication;
    private final CaptchaService captchas;
    private final SmsChallengeService smsChallenges;
    private final AuthRateLimitService rateLimits;
    private final RequestCache requestCache;
    private final LoginAuditRepository audits;
    private final String opsWebUrl;
    private final String adminWebUrl;
    private final String merchantWebUrl;
    private final HttpSessionSecurityContextRepository securityContexts =
            new HttpSessionSecurityContextRepository();

    public LoginController(
            AdminAuthenticationService authentication,
            CaptchaService captchas,
            SmsChallengeService smsChallenges,
            AuthRateLimitService rateLimits,
            RequestCache requestCache,
            LoginAuditRepository audits,
            @Value("${minipay.identity.management-client.post-logout-redirect-uri}") String opsWebUrl,
            @Value("${minipay.identity.admin-client.post-logout-redirect-uri}") String adminWebUrl,
            @Value("${minipay.identity.merchant-client.post-logout-redirect-uri}") String merchantWebUrl) {
        this.authentication = authentication;
        this.captchas = captchas;
        this.smsChallenges = smsChallenges;
        this.rateLimits = rateLimits;
        this.requestCache = requestCache;
        this.audits = audits;
        this.opsWebUrl = opsWebUrl;
        this.adminWebUrl = adminWebUrl;
        this.merchantWebUrl = merchantWebUrl;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(loginPage(null, false))
                .build();
    }

    /**
     * Completes a BFF-initiated logout by clearing the Identity browser session.
     * The target is an enum rather than a caller supplied URL, preventing open redirects.
     */
    @GetMapping("/session/logout")
    public ResponseEntity<Void> logout(
            @RequestParam(defaultValue = "ops") String target,
            HttpServletRequest request,
            HttpServletResponse response) {
        requestCache.removeRequest(request, response);
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        URI destination = switch (target) {
            case "admin" -> URI.create(adminWebUrl);
            case "merchant" -> URI.create(merchantWebUrl);
            default -> URI.create(opsWebUrl);
        };
        return ResponseEntity.status(HttpStatus.FOUND).location(destination).build();
    }

    @PostMapping("/login/password")
    @ResponseBody
    public ResponseEntity<?> passwordLogin(
            @RequestParam String phone,
            @RequestParam String password,
            @RequestParam String captchaId,
            @RequestParam String captchaCode,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            rateLimits.checkLoginAttempt(phone, request.getRemoteAddr());
            captchas.consume(captchaId, captchaCode);
            AdminPrincipal principal = authentication.authenticatePassword(phone, password, metadata(request));
            audit(phone, principal.userId(), "PASSWORD", "SUCCESS", request);
            return success(complete(principal, request, response), request);
        } catch (LoginRejectedException exception) {
            audit(phone, exception.userId(), "PASSWORD", auditResult(exception), request);
            return rejected("password", request);
        }
    }

    @PostMapping("/login/sms")
    @ResponseBody
    public ResponseEntity<?> smsLogin(
            @RequestParam String phone,
            @RequestParam String challengeId,
            @RequestParam String smsCode,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            rateLimits.checkLoginAttempt(phone, request.getRemoteAddr());
            smsChallenges.consume(challengeId, phone, smsCode);
            AdminPrincipal principal = authentication.authenticateSms(phone, metadata(request));
            audit(phone, principal.userId(), "SMS", "SUCCESS", request);
            return success(complete(principal, request, response), request);
        } catch (LoginRejectedException exception) {
            audit(phone, exception.userId(), "SMS", auditResult(exception), request);
            return rejected("sms", request);
        }
    }

    private URI complete(
            AdminPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        UserDetails sessionPrincipal = User.withUsername(principal.userId().toString())
                .password("")
                .authorities(principal.getAuthorities())
                .build();
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                sessionPrincipal, null, sessionPrincipal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            requestCache.removeRequest(request, response);
            return URI.create(savedRequest.getRedirectUrl());
        }
        return URI.create(opsWebUrl);
    }

    private ResponseEntity<?> success(URI redirectUri, HttpServletRequest request) {
        if (isAjax(request)) {
            return ResponseEntity.ok(new LoginAttemptResponse(redirectUri.toString()));
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(redirectUri).build();
    }

    private ResponseEntity<?> rejected(String mode, HttpServletRequest request) {
        if (!isAjax(request)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(loginPage(mode, true))
                    .build();
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "登录失败，请检查手机号、密码或验证码");
        problem.setType(URI.create("https://docs.minipay.local/problems/login-rejected"));
        problem.setTitle("登录失败");
        problem.setProperty("code", "LOGIN_REJECTED");
        problem.setProperty("requestId", requestId(request));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }

    private URI loginPage(String mode, boolean error) {
        String base = opsWebUrl.endsWith("/")
                ? opsWebUrl.substring(0, opsWebUrl.length() - 1)
                : opsWebUrl;
        StringBuilder target = new StringBuilder(base).append("/login");
        if (mode != null) {
            target.append("?mode=").append(mode);
            if (error) target.append("&error=1");
        } else if (error) {
            target.append("?error=1");
        }
        return URI.create(target.toString());
    }

    private String requestId(HttpServletRequest request) {
        return RequestIdFilter.get(request);
    }

    private RequestMetadata metadata(HttpServletRequest request) {
        return new RequestMetadata(
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                requestId(request));
    }

    private void audit(
            String phone,
            UUID userId,
            String method,
            String result,
            HttpServletRequest request) {
        audits.appendLogin(
                userId,
                phone,
                method,
                result,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                requestId(request));
    }

    private String auditResult(LoginRejectedException exception) {
        return switch (exception.code()) {
            case "LOCKED", "DISABLED", "ROLE_DENIED" -> exception.code();
            case "AUTH_RATE_LIMITED", "SMS_RATE_LIMITED" -> "RATE_LIMITED";
            case "CAPTCHA_INVALID", "CAPTCHA_EXPIRED" -> "CAPTCHA_FAILED";
            case "SMS_INVALID" -> "SMS_FAILED";
            default -> "REJECTED";
        };
    }

    public record LoginAttemptResponse(String redirectUrl) {
    }
}
