package com.minipay.consumerbff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.minipay.consumerbff.application.port.IdentityAuthorizationGateway;
import com.minipay.consumerbff.domain.identity.SmsChallenge;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public consumer login calls. Identity mints the authorization code from
 * {@code POST /api/v1/auth/consumer/code/verify} exactly the way the Android client uses it: there
 * is no browser redirect through {@code /oauth2/authorize}, and the BFF supplies its own PKCE pair.
 */
public class HttpIdentityAuthorizationGateway implements IdentityAuthorizationGateway {

    private static final JsonNode MISSING = JsonNodeFactory.instance.missingNode();

    private final WebClient identity;
    private final String clientId;
    private final String redirectUri;

    public HttpIdentityAuthorizationGateway(
            WebClient identity, String clientId, String redirectUri) {
        this.identity = identity;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    @Override
    public Mono<SmsChallenge> sendSmsCode(String mobile, String requestId) {
        return identity.post()
                .uri("/api/v1/auth/consumer/code/send")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mobile", mobile, "purpose", "LOGIN"))
                .exchangeToMono(response -> document(response)
                        .flatMap(document -> response.statusCode().is2xxSuccessful()
                                ? Mono.just(toChallenge(document))
                                : Mono.error(UpstreamProblems.from(
                                        response.statusCode().value(), document,
                                        "SMS_CODE_REQUEST_FAILED"))));
    }

    private SmsChallenge toChallenge(JsonNode document) {
        String challengeId = Values.text(document, "challengeId");
        if (challengeId == null) {
            throw UpstreamProblems.gateway("SMS_CHALLENGE_INVALID", "上游未返回验证码会话标识");
        }
        return new SmsChallenge(
                challengeId,
                Values.instant(document, "expiresAt"),
                // Identity only returns the code when a demo sender is explicitly enabled.
                Values.text(document, "demoCode"));
    }

    @Override
    public Mono<IssuedAuthorizationCode> verifySmsCode(
            String challengeId,
            String code,
            String clientId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String deviceId,
            String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challengeId);
        body.put("code", code);
        body.put("clientId", clientId);
        body.put("redirectUri", redirectUri);
        body.put("codeChallenge", codeChallenge);
        body.put("codeChallengeMethod", codeChallengeMethod);
        body.put("deviceId", deviceId);
        return identity.post()
                .uri("/api/v1/auth/consumer/code/verify")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> document(response)
                        .flatMap(document -> response.statusCode().is2xxSuccessful()
                                ? Mono.just(toAuthorizationCode(document))
                                : Mono.error(UpstreamProblems.from(
                                        response.statusCode().value(), document,
                                        "SMS_CODE_VERIFY_FAILED"))));
    }

    private IssuedAuthorizationCode toAuthorizationCode(JsonNode document) {
        String authorizationCode = Values.text(document, "authorizationCode");
        if (authorizationCode == null) {
            throw UpstreamProblems.gateway("AUTHORIZATION_CODE_INVALID", "上游未返回授权码");
        }
        return new IssuedAuthorizationCode(
                authorizationCode,
                Values.instant(document, "expiresAt"),
                Values.text(document, "userId"),
                // Identity returns the full mobile here; the caller masks it immediately.
                Values.text(document, "phone"),
                Values.bool(document, "payPasswordSet"),
                Values.bool(document, "onboardingRequired"),
                Values.text(document, "realNameStatus"),
                Values.bool(document, "realNameVerified"));
    }

    @Override
    public Mono<OAuthTokenSet> exchangeCode(String code, String codeVerifier, String requestId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        return tokenRequest(form, requestId);
    }

    @Override
    public Mono<OAuthTokenSet> refresh(String refreshToken, String requestId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("refresh_token", refreshToken);
        return tokenRequest(form, requestId);
    }

    @Override
    public Mono<Void> revoke(String refreshToken, String requestId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        return identity.post()
                .uri("/oauth2/revoke")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(response -> response.releaseBody()
                        .then(response.statusCode().is2xxSuccessful()
                                ? Mono.empty()
                                : Mono.error(UpstreamProblems.of(
                                        response.statusCode().value(), "LOGOUT_REVOKE_FAILED"))));
    }

    private Mono<OAuthTokenSet> tokenRequest(MultiValueMap<String, String> form, String requestId) {
        return identity.post()
                .uri("/oauth2/token")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(response -> document(response)
                        .flatMap(document -> response.statusCode().is2xxSuccessful()
                                ? Mono.just(toTokenSet(document))
                                : Mono.error(UpstreamProblems.from(
                                        response.statusCode().value(), document,
                                        "TOKEN_REQUEST_FAILED"))));
    }

    private OAuthTokenSet toTokenSet(JsonNode document) {
        String accessToken = Values.text(document, "access_token");
        if (accessToken == null) {
            throw UpstreamProblems.gateway("TOKEN_RESPONSE_INVALID", "上游未返回访问令牌");
        }
        return new OAuthTokenSet(
                accessToken,
                Values.text(document, "refresh_token"),
                Values.number(document, "expires_in", 600L));
    }

    private static Mono<JsonNode> document(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(JsonNode.class).defaultIfEmpty(MISSING);
    }
}
