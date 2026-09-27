package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import com.minipay.consumerbff.domain.identity.SmsChallenge;
import com.minipay.consumerbff.domain.session.ConsumerSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Consumer H5 session surface. Access and refresh tokens stay in the Redis WebSession; the browser
 * only ever receives the credential-free consumer summary.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class SessionController {

    private final ConsumerSessionService sessions;

    public SessionController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/csrf")
    public Mono<ResponseEntity<Map<String, String>>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> csrfToken = exchange.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null) {
            return Mono.error(new IllegalStateException("CSRF token is unavailable"));
        }
        return csrfToken.map(token -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "headerName", token.getHeaderName(),
                        "parameterName", token.getParameterName(),
                        "token", token.getToken())));
    }

    @PostMapping("/session/sms")
    public Mono<ResponseEntity<Map<String, Object>>> requestSmsCode(
            @RequestBody SmsRequest request, WebSession session, ServerWebExchange exchange) {
        return sessions.requestSmsCode(session, request.mobile(), RequestIds.of(exchange))
                .map(challenge -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .cacheControl(CacheControl.noStore())
                        .body(challengeBody(challenge)));
    }

    private Map<String, Object> challengeBody(SmsChallenge challenge) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challenge.challengeId());
        body.put("expiresAt", challenge.expiresAt());
        // Demo code is only present when Identity explicitly returns one; never invented here.
        if (challenge.demoCode() != null) {
            body.put("demoCode", challenge.demoCode());
        }
        return body;
    }

    @PostMapping("/session")
    public Mono<ResponseEntity<Map<String, Object>>> createSession(
            @RequestBody LoginRequest request, WebSession session, ServerWebExchange exchange) {
        return sessions.completeLogin(
                        session,
                        request.mobile(),
                        request.challengeId(),
                        request.code(),
                        RequestIds.of(exchange))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(sessionBody(value)));
    }

    @GetMapping("/session")
    public Mono<Map<String, Object>> session(WebSession session) {
        return sessions.currentSession(session)
                .map(this::sessionBody)
                .defaultIfEmpty(Map.of("authenticated", false));
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(WebSession session, ServerWebExchange exchange) {
        return sessions.logout(session, RequestIds.of(exchange));
    }

    @PostMapping("/pay-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> setPaymentPassword(
            @RequestBody PaymentPasswordRequest request,
            WebSession session,
            ServerWebExchange exchange) {
        return sessions.setPaymentPassword(
                session, request.paymentPassword(), RequestIds.of(exchange));
    }

    private Map<String, Object> sessionBody(ConsumerSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("userId", session.consumerId().toString());
        body.put("phone", session.maskedPhone());
        body.put("displayName", session.displayName());
        body.put("payPasswordSet", session.payPasswordSet());
        body.put("onboardingRequired", session.onboardingRequired());
        body.put("realNameStatus", session.realNameStatus());
        body.put("realNameVerified", session.realNameVerified());
        return body;
    }

    public record SmsRequest(String mobile) {
    }

    public record LoginRequest(String mobile, String challengeId, String code) {
    }

    /** Never logged, never echoed: consumed in memory for the one-time authorization exchange. */
    public record PaymentPasswordRequest(String paymentPassword) {
    }
}
