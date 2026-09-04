package com.minipay.payment.infrastructure.client;

import com.minipay.payment.application.service.PaymentProblemException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class IdentityInternalClient {
    private static final Pattern PROBLEM_CODE =
            Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private final RestClient identity;
    private final String clientId;
    private final String clientSecret;
    private volatile CachedToken cached;

    public IdentityInternalClient(
            @Value("${minipay.clients.identity.base-url}") String baseUrl,
            @Value("${minipay.clients.identity.client-id}") String clientId,
            @Value("${minipay.clients.identity.client-secret}") String clientSecret) {
        this.identity = RestClient.builder().baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public UUID verifyAndConsume(
            String paymentAuthToken,
            UUID userId,
            String subjectType,
            UUID subjectId,
            long amountCent,
            String deviceId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post()
                    .uri("/internal/v1/payment-authorizations/verify-and-consume")
                    .header("Authorization", "Bearer " + serviceToken())
                    .body(Map.of(
                            "paymentAuthToken", paymentAuthToken,
                            "userId", userId,
                            "subjectType", subjectType,
                            "subjectId", subjectId,
                            "amountCent", amountCent,
                            "deviceId", deviceId))
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("authorizationId") == null) {
                throw new IllegalStateException(
                        "Identity did not return a payment authorization id");
            }
            return UUID.fromString(response.get("authorizationId").toString());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()
                    && exception.getStatusCode().value() != 401
                    && exception.getStatusCode().value() != 403) {
                throw new PaymentProblemException(
                        authorizationFailureCode(exception),
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            throw new PaymentProblemException(
                    verifyFailureCode(exception), HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new PaymentProblemException(
                    "IDENTITY_AUTHORIZATION_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public ConsumerPaymentProfile consumerPaymentProfile(UUID userId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.get()
                    .uri("/internal/v1/consumer-payment-profiles/{userId}", userId)
                    .header("Authorization", "Bearer " + serviceToken())
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("userId") == null
                    || response.get("nickname") == null) {
                throw new IllegalStateException("Identity did not return a consumer payment profile");
            }
            return new ConsumerPaymentProfile(
                    UUID.fromString(response.get("userId").toString()),
                    response.get("nickname").toString(),
                    value(response, "avatarUrl"),
                    value(response, "legalNameMasked"));
        } catch (RestClientResponseException exception) {
            throw new PaymentProblemException(
                    "COLLECTION_RECIPIENT_UNAVAILABLE",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
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
            form.add("scope", "identity.payment-authorization.verify "
                    + "identity.consumer-payment-profile.read");
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = identity.post()
                        .uri("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                        .body(form)
                        .retrieve()
                        .body(Map.class);
                if (response == null || response.get("access_token") == null) {
                    throw new PaymentProblemException(
                            "IDENTITY_AUTHORIZATION_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
                }
                long expiresIn = response.get("expires_in") instanceof Number number
                        ? number.longValue() : 300;
                cached = new CachedToken(
                        response.get("access_token").toString(),
                        Instant.now().plusSeconds(expiresIn));
                return cached.value();
            } catch (RestClientResponseException exception) {
                throw new PaymentProblemException(
                        serviceTokenFailureCode(exception), HttpStatus.SERVICE_UNAVAILABLE);
            } catch (RestClientException exception) {
                throw new PaymentProblemException(
                        "IDENTITY_AUTHORIZATION_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    private static String authorizationFailureCode(RestClientResponseException exception) {
        Matcher matcher = PROBLEM_CODE.matcher(exception.getResponseBodyAsString());
        if (matcher.find()) {
            String code = matcher.group(1);
            if ("PAYMENT_AUTHORIZATION_INVALID".equals(code)
                    || "PAYMENT_AUTHORIZATION_EXPIRED".equals(code)
                    || "PAYMENT_AUTHORIZATION_CONSUMED".equals(code)) {
                return code;
            }
        }
        return "PAYMENT_AUTHORIZATION_INVALID";
    }

    static String serviceTokenFailureCode(RestClientResponseException exception) {
        return exception.getStatusCode().value() == 400 || exception.getStatusCode().value() == 401
                ? "IDENTITY_CLIENT_CREDENTIALS_REJECTED"
                : "IDENTITY_AUTHORIZATION_SERVICE_UNAVAILABLE";
    }

    static String verifyFailureCode(RestClientResponseException exception) {
        return exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403
                ? "IDENTITY_AUTHORIZATION_ACCESS_DENIED"
                : "IDENTITY_AUTHORIZATION_SERVICE_UNAVAILABLE";
    }

    private static String value(Map<String, Object> response, String key) {
        Object value = response.get(key);
        return value == null ? null : value.toString();
    }

    public record ConsumerPaymentProfile(
            UUID userId, String nickname, String avatarUrl, String legalNameMasked) {
    }
}
