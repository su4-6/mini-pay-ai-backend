package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class PublicRefreshClientAuthenticationTest {
    @Test
    void convertsAndAuthenticatesPublicRefreshRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("grant_type", "refresh_token");
        request.addParameter("client_id", "minipay-android");
        OAuth2ClientAuthenticationToken converted =
                (OAuth2ClientAuthenticationToken)
                        new PublicRefreshClientAuthenticationConverter().convert(request);
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        when(clients.findByClientId("minipay-android")).thenReturn(publicClient());

        OAuth2ClientAuthenticationToken authenticated =
                (OAuth2ClientAuthenticationToken)
                        new PublicRefreshClientAuthenticationProvider(clients)
                                .authenticate(converted);

        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getRegisteredClient().getClientId())
                .isEqualTo("minipay-android");
    }

    @Test
    void rejectsClientThatIsNotRegisteredForPublicRefresh() {
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        OAuth2ClientAuthenticationToken token = new OAuth2ClientAuthenticationToken(
                "unknown",
                ClientAuthenticationMethod.NONE,
                null,
                java.util.Map.of("grant_type", "refresh_token"));

        assertThatThrownBy(() ->
                new PublicRefreshClientAuthenticationProvider(clients).authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void authenticatesPublicClientAtTokenRevocationEndpoint() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/revoke");
        request.addParameter("client_id", "minipay-android");
        request.addParameter("token", "refresh-token-value");
        OAuth2ClientAuthenticationToken converted = (OAuth2ClientAuthenticationToken)
                new PublicRefreshClientAuthenticationConverter().convert(request);
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        when(clients.findByClientId("minipay-android")).thenReturn(publicClient());

        OAuth2ClientAuthenticationToken authenticated = (OAuth2ClientAuthenticationToken)
                new PublicRefreshClientAuthenticationProvider(clients).authenticate(converted);

        assertThat(authenticated.isAuthenticated()).isTrue();
    }

    private RegisteredClient publicClient() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("minipay-android")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("com.minipay.mobile:/oauth2redirect")
                .build();
    }
}
