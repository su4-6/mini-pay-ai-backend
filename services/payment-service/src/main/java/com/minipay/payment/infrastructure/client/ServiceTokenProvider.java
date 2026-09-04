package com.minipay.payment.infrastructure.client;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class ServiceTokenProvider {
    private final RestClient identity;
    private final String clientId;
    private final String clientSecret;
    private volatile CachedToken cached;

    public ServiceTokenProvider(
            @Value("${minipay.clients.identity-token-url}") String tokenUrl,
            @Value("${minipay.clients.wallet.client-id}") String clientId,
            @Value("${minipay.clients.wallet.client-secret}") String clientSecret) {
        this.identity = RestClient.builder().baseUrl(tokenUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String walletToken() {
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
            form.add("scope", "wallet.account.resolve wallet.posting.write wallet.tcc");
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post()
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

    private record CachedToken(String value, Instant expiresAt) {
    }
}
