package com.minipay.payment.infrastructure.client;

import com.minipay.payment.application.service.OpsBusinessException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Talks to identity-service' internal account endpoints on behalf of ops flows.
 * Used by BD 代建 merchant creation and merchant-apply approval to resolve the
 * merchant owner's identity account (find existing or create a SMS-only one).
 */
@Component
public class IdentityServiceClient {
    private static final String SCOPE = "identity.account.manage";

    private final RestClient identity;
    private final String clientId;
    private final String clientSecret;
    private volatile CachedToken cached;

    public IdentityServiceClient(
            @Value("${minipay.clients.identity.base-url}") String baseUrl,
            @Value("${minipay.clients.identity.client-id}") String clientId,
            @Value("${minipay.clients.identity.client-secret}") String clientSecret) {
        this.identity = RestClient.builder().baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public MerchantOwner resolveMerchantOwner(
            String mobile, String displayName, String requestId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post()
                    .uri("/internal/v1/accounts/merchant-owner")
                    .header("Authorization", "Bearer " + serviceToken())
                    .body(Map.of(
                            "mobile", mobile,
                            "displayName", displayName == null ? "" : displayName,
                            "requestId", requestId))
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("userId") == null) {
                throw new IllegalStateException(
                        "Identity did not return a merchant owner user id");
            }
            return new MerchantOwner(
                    UUID.fromString(response.get("userId").toString()),
                    response.get("loginName") == null ? null
                            : response.get("loginName").toString(),
                    Boolean.TRUE.equals(response.get("newlyCreated")));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                throw new OpsBusinessException(HttpStatus.CONFLICT,
                        "MERCHANT_OWNER_DISABLED",
                        "The account for this mobile is disabled; contact the platform");
            }
            if (exception.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new OpsBusinessException(HttpStatus.BAD_REQUEST,
                        "INVALID_MERCHANT_OWNER_MOBILE",
                        "Identity rejected the mobile number");
            }
            throw new OpsBusinessException(HttpStatus.BAD_GATEWAY, "IDENTITY_UNAVAILABLE",
                    "Identity service could not process the account request");
        } catch (RestClientException exception) {
            throw new OpsBusinessException(HttpStatus.BAD_GATEWAY, "IDENTITY_UNAVAILABLE",
                    "Identity service is unreachable");
        }
    }

    private String serviceToken() {
        CachedToken current = cached;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(20))) {
            return current.value();
        }
        synchronized (this) {
            current = cached;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(20))) {
                return current.value();
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("scope", SCOPE);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw new IllegalStateException("Identity did not return a service access token");
            }
            long expiresIn = response.get("expires_in") instanceof Number number
                    ? number.longValue() : 300;
            cached = new CachedToken(
                    response.get("access_token").toString(),
                    Instant.now().plusSeconds(expiresIn));
            return cached.value();
        }
    }

    public record MerchantOwner(UUID userId, String loginName, boolean newlyCreated) {
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
