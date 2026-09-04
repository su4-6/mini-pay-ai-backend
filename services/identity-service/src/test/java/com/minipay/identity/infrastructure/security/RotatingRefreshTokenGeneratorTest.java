package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;

class RotatingRefreshTokenGeneratorTest {
    @Test
    void issuesThirtyDayRefreshTokenForPublicAuthorizationCodeClient() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("minipay-android")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("com.minipay.mobile:/oauth2redirect")
                .tokenSettings(TokenSettings.builder()
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        var context = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(new TestingAuthenticationToken("consumer", null))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrant(new TestingAuthenticationToken("client", null))
                .tokenType(OAuth2TokenType.REFRESH_TOKEN)
                .build();
        Instant before = Instant.now();

        var token = new RotatingRefreshTokenGenerator().generate(context);

        assertThat(token).isNotNull();
        assertThat(token.getTokenValue())
                .hasSize(128)
                .matches("^[A-Za-z0-9_-]+$");
        assertThat(token.getExpiresAt())
                .isBetween(before.plus(Duration.ofDays(30)).minusSeconds(1),
                        Instant.now().plus(Duration.ofDays(30)).plusSeconds(1));
    }
}
