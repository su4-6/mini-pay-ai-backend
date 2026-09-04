package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class DelegationTokenExchangeValidatorTest {
    private static final String SUBJECT_TOKEN = "android-access-token";
    private static final String RUN_ID = "019fdf36-95c5-7803-b743-97f4528462a9";

    private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
    private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    private final RegisteredClient android = RegisteredClient.withId("android-id")
            .clientId("minipay-android")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("com.minipay.mobile:/oauth2redirect")
            .scope("agent.conversation")
            .build();
    private final RegisteredClient agent = RegisteredClient.withId("agent-id")
            .clientId("minipay-agent-service")
            .clientSecret("secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .scope("wallet.agent.summary")
            .build();
    private final DelegationTokenExchangeValidator validator = new DelegationTokenExchangeValidator(
            authorizations, clients, "minipay-agent-service", "minipay-android");

    @BeforeEach
    void subjectSession() {
        when(authorizations.findByToken(SUBJECT_TOKEN, OAuth2TokenType.ACCESS_TOKEN))
                .thenReturn(subjectAuthorization(Map.of(
                        "aud", Set.of("consumer-api"),
                        "device_id", "device-01")));
        when(clients.findById("android-id")).thenReturn(android);
    }

    @Test
    void acceptsOneLeastPrivilegeAudienceBoundToRunAndPurpose() {
        assertThat(validator.authenticate(exchange(
                Set.of("wallet-internal"),
                Set.of("wallet.agent.summary"),
                Map.of("purpose", "WALLET_QUERY", "run_id", RUN_ID))))
                .isNull();
    }

    @Test
    void acceptsPaymentTransferReadDelegation() {
        assertThat(validator.authenticate(exchange(
                Set.of("payment-internal"),
                Set.of("payment.agent.transfer.read"),
                Map.of("purpose", "TRANSFER_QUERY", "run_id", RUN_ID))))
                .isNull();
    }

    @Test
    void rejectsCrossAudienceScopeEscalation() {
        assertThatThrownBy(() -> validator.authenticate(exchange(
                Set.of("wallet-internal"),
                Set.of("payment.agent.transfer.prepare"),
                Map.of("purpose", "WALLET_QUERY", "run_id", RUN_ID))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error -> ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("invalid_scope");
    }

    @Test
    void rejectsSubjectWithoutDeviceBinding() {
        when(authorizations.findByToken(SUBJECT_TOKEN, OAuth2TokenType.ACCESS_TOKEN))
                .thenReturn(subjectAuthorization(Map.of("aud", Set.of("consumer-api"))));

        assertThatThrownBy(() -> validator.authenticate(exchange(
                Set.of("wallet-internal"),
                Set.of("wallet.agent.summary"),
                Map.of("purpose", "WALLET_QUERY", "run_id", RUN_ID))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error -> ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("invalid_grant");
    }

    @Test
    void rejectsMissingRunBinding() {
        assertThatThrownBy(() -> validator.authenticate(exchange(
                Set.of("wallet-internal"),
                Set.of("wallet.agent.summary"),
                Map.of("purpose", "WALLET_QUERY"))))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error -> ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("invalid_request");
    }

    private OAuth2TokenExchangeAuthenticationToken exchange(
            Set<String> audiences,
            Set<String> scopes,
            Map<String, Object> additionalParameters) {
        OAuth2ClientAuthenticationToken client = new OAuth2ClientAuthenticationToken(
                agent, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
        return new OAuth2TokenExchangeAuthenticationToken(
                "urn:ietf:params:oauth:token-type:access_token",
                SUBJECT_TOKEN,
                "urn:ietf:params:oauth:token-type:access_token",
                client,
                null,
                null,
                Set.of(),
                audiences,
                scopes,
                additionalParameters);
    }

    private OAuth2Authorization subjectAuthorization(Map<String, Object> claims) {
        Instant now = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                SUBJECT_TOKEN,
                now.minusSeconds(1),
                now.plusSeconds(600),
                Set.of("agent.conversation"));
        return OAuth2Authorization.withRegisteredClient(android)
                .principalName(UUID.randomUUID().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("agent.conversation"))
                .token(accessToken, metadata -> metadata.put(
                        OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claims))
                .build();
    }
}
