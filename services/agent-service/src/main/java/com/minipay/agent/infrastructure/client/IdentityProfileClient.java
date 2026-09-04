package com.minipay.agent.infrastructure.client;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class IdentityProfileClient {
    private final RestClient identity;
    private final String clientId;
    private final String clientSecret;
    private volatile CachedToken cached;

    public IdentityProfileClient(
            @Value("${minipay.agent.profile-identity.base-url}") String baseUrl,
            @Value("${minipay.agent.profile-identity.client-id}") String clientId,
            @Value("${minipay.agent.profile-identity.client-secret}") String clientSecret) {
        this.identity = RestClient.builder().baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Presentation-only lookup. Identity failures intentionally degrade to stored chat data. */
    public Map<UUID, Profile> findProfiles(List<UUID> userIds) {
        List<UUID> distinct = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        Map<UUID, Profile> result = new LinkedHashMap<>();
        try {
            for (int start = 0; start < distinct.size(); start += 20) {
                List<UUID> batch = distinct.subList(start, Math.min(start + 20, distinct.size()));
                String query = batch.stream().map(id -> "userId=" + id)
                        .collect(java.util.stream.Collectors.joining("&"));
                List<Map<String, Object>> response = identity.get()
                        .uri("/internal/v1/consumer-payment-profiles?" + query)
                        .header("Authorization", "Bearer " + serviceToken())
                        .retrieve().body(new ParameterizedTypeReference<>() {});
                if (response == null) continue;
                for (Map<String, Object> item : response) {
                    UUID userId = UUID.fromString(item.get("userId").toString());
                    result.put(userId, new Profile(userId, value(item, "nickname"),
                            value(item, "avatarUrl"), instant(item, "avatarUrlExpiresAt")));
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private String serviceToken() {
        CachedToken current = cached;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(20))) return current.value();
        synchronized (this) {
            current = cached;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(20))) return current.value();
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("scope", "identity.consumer-payment-profile.read");
            @SuppressWarnings("unchecked")
            Map<String, Object> response = identity.post().uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .body(form).retrieve().body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw new IllegalStateException("Identity did not return a service token");
            }
            long expiresIn = response.get("expires_in") instanceof Number number ? number.longValue() : 300L;
            cached = new CachedToken(response.get("access_token").toString(), Instant.now().plusSeconds(expiresIn));
            return cached.value();
        }
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static Instant instant(Map<String, Object> map, String key) {
        String value = value(map, key);
        return value == null ? null : Instant.parse(value);
    }

    public record Profile(UUID userId, String nickname, String avatarUrl, Instant avatarUrlExpiresAt) {}
    private record CachedToken(String value, Instant expiresAt) {}
}
