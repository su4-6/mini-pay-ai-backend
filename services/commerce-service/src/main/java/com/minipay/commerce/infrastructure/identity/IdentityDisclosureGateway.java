package com.minipay.commerce.infrastructure.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.CommerceApplicationException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class IdentityDisclosureGateway {
    private final RestClient identity;
    private final ObjectMapper json;
    private final String clientId;
    private final String clientSecret;
    private volatile CachedToken cachedToken;

    public IdentityDisclosureGateway(
            RestClient.Builder builder,
            ObjectMapper json,
            @Value("${minipay.commerce.identity.base-url:http://localhost:8081}") String baseUrl,
            @Value("${minipay.commerce.identity.client-id:minipay-commerce-to-identity}") String clientId,
            @Value("${minipay.commerce.identity.client-secret:}") String clientSecret) {
        this.identity = builder.baseUrl(baseUrl).build();
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public Disclosure disclosure(UUID userId) {
        try {
            return identity.get()
                    .uri("/internal/v1/users/{userId}/application-disclosures/yshop-food", userId)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve().body(Disclosure.class);
        } catch (RestClientResponseException exception) {
            String code = problemCode(exception);
            if ("PHONE_UPGRADE_REQUIRED".equals(code)) {
                throw problem("COMMERCE_PHONE_UPGRADE_REQUIRED", "请先验证当前手机号");
            }
            if ("APPLICATION_AUTHORIZATION_REQUIRED".equals(code)) {
                throw problem("COMMERCE_FOOD_AUTHORIZATION_REQUIRED", "请先授权外卖服务");
            }
            throw problem("COMMERCE_IDENTITY_UNAVAILABLE", "用户授权资料暂不可用");
        } catch (CommerceApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw problem("COMMERCE_IDENTITY_UNAVAILABLE", "用户授权资料暂不可用");
        }
    }

    private String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(10))) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(10))) {
                return current.value();
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                throw problem("COMMERCE_IDENTITY_NOT_CONFIGURED", "用户授权服务尚未配置");
            }
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("scope", "identity.application-disclosure.read");
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post().uri("/oauth2/token")
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw problem("COMMERCE_IDENTITY_UNAVAILABLE", "用户授权服务暂不可用");
            }
            long ttl = response.get("expires_in") instanceof Number value ? value.longValue() : 60;
            cachedToken = new CachedToken(response.get("access_token").toString(),
                    Instant.now().plusSeconds(Math.max(30, ttl)));
            return cachedToken.value();
        }
    }

    private String problemCode(RestClientResponseException exception) {
        try {
            JsonNode body = json.readTree(exception.getResponseBodyAsByteArray());
            return body.path("code").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static CommerceApplicationException problem(String code, String message) {
        return new CommerceApplicationException(code, message);
    }

    public record Disclosure(
            UUID authorizationId, String applicationId, UUID subject, String nickname,
            String avatarFetchUrl, String phone, long profileVersion, Set<String> grantedScopes) { }
    private record CachedToken(String value, Instant expiresAt) { }
}
