package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class DigestingOAuth2AuthorizationServiceTest {
    @Test
    void persistsOnlyRefreshTokenDigest() {
        OAuth2AuthorizationService delegate = mock(OAuth2AuthorizationService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DigestingOAuth2AuthorizationService service =
                new DigestingOAuth2AuthorizationService(delegate, jdbcTemplate, "unit-test-token-pepper");
        RegisteredClient client = RegisteredClient.withId("client-id")
                .clientId("client")
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .build();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("admin-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .refreshToken(new OAuth2RefreshToken(
                        "raw-refresh-token",
                        Instant.now(),
                        Instant.now().plusSeconds(600)))
                .build();

        service.save(authorization);

        ArgumentCaptor<OAuth2Authorization> captor =
                ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(delegate).save(captor.capture());
        String persisted = captor.getValue().getRefreshToken().getToken().getTokenValue();
        assertThat(persisted)
                .startsWith("hmac-sha256:")
                .doesNotContain("raw-refresh-token");
    }

    @Test
    void marksRefreshTokenHistoryRevokedWhenAuthorizationServerInvalidatesToken() {
        OAuth2AuthorizationService delegate = mock(OAuth2AuthorizationService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DigestingOAuth2AuthorizationService service =
                new DigestingOAuth2AuthorizationService(delegate, jdbcTemplate, "unit-test-token-pepper");
        RegisteredClient client = RegisteredClient.withId("client-id")
                .clientId("client")
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .build();
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                "raw-refresh-token",
                Instant.now(),
                Instant.now().plusSeconds(600));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("admin-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(refreshToken, metadata -> metadata.put(
                        OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true))
                .build();

        service.save(authorization);

        ArgumentCaptor<OAuth2Authorization> captor =
                ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(delegate).save(captor.capture());
        String persisted = captor.getValue().getRefreshToken().getToken().getTokenValue();
        assertThat(persisted).startsWith("hmac-sha256:");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("revoked_at"),
                org.mockito.ArgumentMatchers.eq(persisted),
                org.mockito.ArgumentMatchers.eq(authorization.getId()));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("oauth2_refresh_token_family"),
                org.mockito.ArgumentMatchers.eq(authorization.getId()));
    }
}
