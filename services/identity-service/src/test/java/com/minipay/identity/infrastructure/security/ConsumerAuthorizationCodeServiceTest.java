package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

class ConsumerAuthorizationCodeServiceTest {
    private static final String CLIENT_ID = "minipay-android";
    private static final String REDIRECT_URI = "com.minipay.mobile:/oauth2redirect";
    private static final String CHALLENGE = "A".repeat(43);
    private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
    private final ConsumerAuthorizationCodeService service = new ConsumerAuthorizationCodeService(
            clients,
            authorizations,
            "http://localhost:8081",
            CLIENT_ID,
            REDIRECT_URI,
            Duration.ofSeconds(60));

    @Test
    void issuesSingleUseAuthorizationCodeBoundToS256ChallengeAndDevice() {
        when(clients.findByClientId(CLIENT_ID)).thenReturn(androidClient());
        ConsumerPrincipal consumer =
                new ConsumerPrincipal(UUID.randomUUID(), "米灵用户", false);
        Instant before = Instant.now();

        ConsumerAuthorizationCodeService.IssuedAuthorizationCode issued = service.issue(
                consumer,
                CLIENT_ID,
                REDIRECT_URI,
                CHALLENGE,
                "S256",
                "installation-id");

        ArgumentCaptor<OAuth2Authorization> saved =
                ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(authorizations).save(saved.capture());
        OAuth2Authorization authorization = saved.getValue();
        OAuth2AuthorizationRequest request =
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        assertThat(request).isNotNull();
        assertThat(request.getAdditionalParameters())
                .containsEntry("code_challenge", CHALLENGE)
                .containsEntry("code_challenge_method", "S256");
        assertThat(authorization.<String>getAttribute(
                ConsumerAuthorizationCodeService.DEVICE_ID_ATTRIBUTE))
                .isEqualTo("installation-id");
        assertThat(authorization.getAuthorizedScopes())
                .contains("identity.profile.read", "identity.profile.write", "commerce.use");
        assertThat(authorization.getToken(OAuth2AuthorizationCode.class).getToken().getTokenValue())
                .isEqualTo(issued.authorizationCode());
        assertThat(issued.expiresAt())
                .isBetween(before.plusSeconds(59), Instant.now().plusSeconds(61));
    }

    @Test
    void rejectsPlainOrMalformedPkceBeforeSavingCode() {
        when(clients.findByClientId(CLIENT_ID)).thenReturn(androidClient());
        ConsumerPrincipal consumer =
                new ConsumerPrincipal(UUID.randomUUID(), "米灵用户", false);

        assertThatThrownBy(() -> service.issue(
                consumer, CLIENT_ID, REDIRECT_URI, "short", "plain", "device"))
                .isInstanceOf(LoginRejectedException.class)
                .extracting("code")
                .isEqualTo("PKCE_INVALID");
        verify(authorizations, never()).save(any());
    }

    private RegisteredClient androidClient() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("identity.profile.read")
                .scope("identity.profile.write")
                .scope("wallet.read")
                .scope("agent.conversation")
                .scope("commerce.use")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build();
    }
}
