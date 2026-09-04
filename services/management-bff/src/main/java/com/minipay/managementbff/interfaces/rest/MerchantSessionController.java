package com.minipay.managementbff.interfaces.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/** Browser-facing merchant SMS login; OAuth tokens remain in the server WebSession. */
@RestController
@RequestMapping("/api/v1")
public class MerchantSessionController {
    private static final String ACCESS_TOKEN = "merchant.access-token";
    private static final String ACCESS_TOKEN_EXPIRES_AT = "merchant.access-token-expires-at";
    private static final String LOGIN_PHONE = "merchant.login-phone";
    private static final String PASSWORD_CONFIGURED = "merchant.password-configured";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WebClient identity;
    private final String clientId;
    private final String redirectUri;

    public MerchantSessionController(
            @Value("${minipay.identity-internal-url}") String identityUrl,
            @Value("${minipay.merchant-oauth-client-id}") String clientId,
            @Value("${minipay.merchant-oauth-redirect-uri}") String redirectUri) {
        this.identity = WebClient.builder().baseUrl(identityUrl).build();
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    @PostMapping("/merchant-auth/code/send")
    public Mono<CodeChallengeResponse> send(@RequestBody SendCodeRequest request) {
        return identity.post().uri("/api/v1/auth/merchant/code/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mobile", request.mobile(), "captchaId", request.captchaId(),
                        "captchaCode", request.captchaCode()))
                .retrieve().bodyToMono(IdentityChallenge.class)
                .map(value -> new CodeChallengeResponse(value.challengeId(), value.expiresAt(), value.resendAt()));
    }

    @PostMapping("/merchant-auth/captcha")
    public Mono<CaptchaResponse> createCaptcha() {
        return identity.post().uri("/api/v1/auth/captchas")
                .retrieve().bodyToMono(IdentityCaptcha.class)
                .map(value -> new CaptchaResponse(value.captchaId(),
                        "/api/v1/merchant-auth/captcha/" + value.captchaId() + "/image", value.expiresAt()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/merchant-auth/captcha/{captchaId}/image")
    public Mono<ResponseEntity<byte[]>> captchaImage(
            @org.springframework.web.bind.annotation.PathVariable String captchaId) {
        return identity.get().uri("/api/v1/auth/captchas/{captchaId}/image", captchaId)
                .exchangeToMono(response -> response.bodyToMono(byte[].class)
                        .map(bytes -> ResponseEntity.status(response.statusCode())
                                .contentType(response.headers().contentType().orElse(MediaType.IMAGE_PNG))
                                .cacheControl(org.springframework.http.CacheControl.noStore()).body(bytes)));
    }

    @PostMapping("/merchant-auth/code/verify")
    public Mono<MerchantSessionResponse> verify(@RequestBody VerifyCodeRequest request, WebSession session) {
        String verifier = verifier();
        return identity.post().uri("/api/v1/auth/consumer/code/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "challengeId", request.challengeId(), "code", request.code(), "clientId", clientId,
                        "redirectUri", redirectUri, "codeChallenge", challenge(verifier),
                        "codeChallengeMethod", "S256", "deviceId", request.deviceId()))
                .retrieve().bodyToMono(AuthorizationCodeResponse.class)
                .flatMap(code -> exchange(code.authorizationCode(), verifier)
                        .flatMap(token -> request.resetPassword() == null || request.resetPassword().isBlank()
                                ? Mono.just(token) : resetPassword(token.accessToken(), request.resetPassword()).thenReturn(token))
                        .map(token -> store(session, token, code.phone(),
                                code.merchantPasswordConfigured()
                                        || request.resetPassword() != null && !request.resetPassword().isBlank())));
    }

    @PostMapping("/merchant-auth/password/verify")
    public Mono<MerchantSessionResponse> verifyPassword(@RequestBody PasswordLoginRequest request, WebSession session) {
        String verifier = verifier();
        return identity.post().uri("/api/v1/auth/merchant/password/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "mobile", request.mobile(), "password", request.password(),
                        "captchaId", request.captchaId(), "captchaCode", request.captchaCode(), "clientId", clientId,
                        "redirectUri", redirectUri, "codeChallenge", challenge(verifier),
                        "codeChallengeMethod", "S256", "deviceId", request.deviceId()))
                .retrieve().bodyToMono(AuthorizationCodeResponse.class)
                .flatMap(code -> exchange(code.authorizationCode(), verifier))
                .map(token -> store(session, token, request.mobile(), true));
    }

    @RequestMapping("/merchant-session")
    public MerchantSessionResponse session(WebSession session) {
        String token = session.getAttribute(ACCESS_TOKEN);
        Instant expiresAt = session.getAttribute(ACCESS_TOKEN_EXPIRES_AT);
        if (token == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            session.getAttributes().remove(ACCESS_TOKEN);
            session.getAttributes().remove(ACCESS_TOKEN_EXPIRES_AT);
            session.getAttributes().remove(PASSWORD_CONFIGURED);
            return new MerchantSessionResponse(false, null, null, false);
        }
        return new MerchantSessionResponse(true, expiresAt, session.getAttribute(LOGIN_PHONE),
                Boolean.TRUE.equals(session.getAttribute(PASSWORD_CONFIGURED)));
    }

    /** Removes the merchant-only server session; it never affects the operations portal session. */
    @DeleteMapping("/merchant-session")
    public Mono<Void> logout(WebSession session) {
        session.getAttributes().remove(ACCESS_TOKEN);
        session.getAttributes().remove(ACCESS_TOKEN_EXPIRES_AT);
        return session.invalidate();
    }

    @PutMapping("/merchant-auth/password")
    public Mono<Void> changePassword(
            @RequestBody ChangePasswordRequest request, WebSession session) {
        String token = session.getAttribute(ACCESS_TOKEN);
        Instant expiresAt = session.getAttribute(ACCESS_TOKEN_EXPIRES_AT);
        if (token == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            return Mono.error(new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "MERCHANT_REAUTHENTICATION_REQUIRED"));
        }
        return identity.put().uri("/api/v1/auth/merchant/password")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "currentPassword", request.currentPassword() == null ? "" : request.currentPassword(),
                        "newPassword", request.newPassword()))
                .retrieve().bodyToMono(Void.class)
                .doOnSuccess(ignored -> session.getAttributes().put(PASSWORD_CONFIGURED, true));
    }

    private Mono<TokenResponse> exchange(String code, String verifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code"); form.add("client_id", clientId);
        form.add("code", code); form.add("redirect_uri", redirectUri); form.add("code_verifier", verifier);
        return identity.post().uri("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form).retrieve().bodyToMono(TokenResponse.class);
    }

    private Mono<Void> resetPassword(String accessToken, String password) {
        return identity.put().uri("/api/v1/auth/merchant/password/reset")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("newPassword", password))
                .retrieve().bodyToMono(Void.class);
    }

    private MerchantSessionResponse store(
            WebSession session, TokenResponse token, String phone, boolean passwordConfigured) {
        Instant expiresAt = Instant.now().plusSeconds(token.expiresIn());
        session.getAttributes().put(ACCESS_TOKEN, token.accessToken());
        session.getAttributes().put(ACCESS_TOKEN_EXPIRES_AT, expiresAt);
        session.getAttributes().put(LOGIN_PHONE, phone);
        session.getAttributes().put(PASSWORD_CONFIGURED, passwordConfigured);
        return new MerchantSessionResponse(true, expiresAt, phone, passwordConfigured);
    }

    private static String verifier() { byte[] bytes = new byte[48]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception exception) { throw new IllegalStateException("PKCE challenge unavailable", exception); }
    }

    public record SendCodeRequest(String mobile, String captchaId, String captchaCode) { }
    public record VerifyCodeRequest(String challengeId, String code, String deviceId, String resetPassword) { }
    public record PasswordLoginRequest(String mobile, String password, String captchaId, String captchaCode, String deviceId) { }
    public record ChangePasswordRequest(String currentPassword, String newPassword) { }
    public record CodeChallengeResponse(String challengeId, Instant expiresAt, Instant resendAt) { }
    public record MerchantSessionResponse(
            boolean authenticated, Instant expiresAt, String phone, boolean passwordConfigured) { }
    private record IdentityChallenge(String challengeId, Instant expiresAt, Instant resendAt) { }
    private record IdentityCaptcha(String captchaId, String imageUrl, Instant expiresAt) { }
    public record CaptchaResponse(String captchaId, String imageUrl, Instant expiresAt) { }
    private record AuthorizationCodeResponse(
            String authorizationCode, String phone, boolean merchantPasswordConfigured) { }
    private record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) { }
}
