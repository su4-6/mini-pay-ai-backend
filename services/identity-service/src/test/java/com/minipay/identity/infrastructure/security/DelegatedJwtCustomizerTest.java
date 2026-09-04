package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class DelegatedJwtCustomizerTest {
    @Test
    void delegatedTokenContainsOnlyBoundActorPurposeRunAndDeviceClaims() {
        AdminAccountRepository admins = mock(AdminAccountRepository.class);
        ConsumerAccountRepository consumers = mock(ConsumerAccountRepository.class);
        var customizer = new SecurityConfiguration().jwtCustomizer(admins, consumers);
        RegisteredClient agent = RegisteredClient.withId("agent-id")
                .clientId("minipay-agent-service")
                .clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .scope("wallet.agent.summary")
                .build();
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OAuth2ClientAuthenticationToken client = new OAuth2ClientAuthenticationToken(
                agent, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
        OAuth2TokenExchangeAuthenticationToken exchange = new OAuth2TokenExchangeAuthenticationToken(
                "urn:ietf:params:oauth:token-type:access_token",
                "subject",
                "urn:ietf:params:oauth:token-type:access_token",
                client,
                null,
                null,
                Set.of(),
                Set.of("wallet-internal"),
                Set.of("wallet.agent.summary"),
                Map.of("purpose", "WALLET_QUERY", "run_id", runId.toString()));
        OAuth2Authorization subject = subjectAuthorization(userId);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(userId.toString());
        JwtEncodingContext context = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256), claims)
                .registeredClient(agent)
                .principal(client)
                .authorization(subject)
                .authorizedScopes(Set.of("wallet.agent.summary"))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .authorizationGrant(exchange)
                .build();

        customizer.customize(context);

        Map<String, Object> result = claims.build().getClaims();
        assertThat(result)
                .containsEntry("sub", userId.toString())
                .containsEntry("azp", "minipay-agent-service")
                .containsEntry("purpose", "WALLET_QUERY")
                .containsEntry("run_id", runId.toString())
                .containsEntry("device_id", "device-01")
                .containsEntry("user_id", userId.toString())
                .doesNotContainKeys("display_name", "pay_password_set", "real_name_status");
        assertThat(result.get("aud")).isEqualTo(java.util.List.of("wallet-internal"));
        assertThat(result.get("act")).isEqualTo(Map.of("sub", "minipay-agent-service"));
        verifyNoInteractions(admins, consumers);
    }

    private OAuth2Authorization subjectAuthorization(UUID userId) {
        RegisteredClient android = RegisteredClient.withId("android-id")
                .clientId("minipay-android")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("com.minipay.mobile:/oauth2redirect")
                .scope("agent.conversation")
                .build();
        Instant now = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "subject",
                now.minusSeconds(1),
                now.plusSeconds(600),
                Set.of("agent.conversation"));
        return OAuth2Authorization.withRegisteredClient(android)
                .principalName(userId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("agent.conversation"))
                .token(accessToken, metadata -> metadata.put(
                        OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                        Map.of(
                                "aud", java.util.List.of("consumer-api"),
                                "device_id", "device-01")))
                .build();
    }
}
