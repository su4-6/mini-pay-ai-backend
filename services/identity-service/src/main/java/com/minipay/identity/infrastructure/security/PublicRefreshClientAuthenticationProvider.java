package com.minipay.identity.infrastructure.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

public final class PublicRefreshClientAuthenticationProvider
        implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    public PublicRefreshClientAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        OAuth2ClientAuthenticationToken clientAuthentication =
                (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(
                clientAuthentication.getClientAuthenticationMethod())
                || !isSupportedOperation(clientAuthentication)) {
            return null;
        }

        RegisteredClient client =
                clients.findByClientId(clientAuthentication.getPrincipal().toString());
        if (client == null
                || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
        }
        return new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.NONE, null);
    }

    private boolean isSupportedOperation(OAuth2ClientAuthenticationToken authentication) {
        Object operation = authentication.getAdditionalParameters()
                .get(PublicRefreshClientAuthenticationConverter.PUBLIC_OPERATION);
        return PublicRefreshClientAuthenticationConverter.REFRESH.equals(operation)
                || PublicRefreshClientAuthenticationConverter.REVOCATION.equals(operation)
                || AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(
                authentication.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
