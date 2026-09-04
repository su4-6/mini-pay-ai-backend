package com.minipay.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * Authenticates a native public client at the refresh-token endpoint using its
 * client_id, as required for clients that cannot keep a client secret.
 */
public final class PublicRefreshClientAuthenticationConverter
        implements AuthenticationConverter {
    static final String PUBLIC_OPERATION = "minipay.public-client-operation";
    static final String REFRESH = "refresh";
    static final String REVOCATION = "revocation";

    @Override
    public Authentication convert(HttpServletRequest request) {
        boolean refresh = AuthorizationGrantType.REFRESH_TOKEN.getValue()
                .equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
        boolean revocation = request.getRequestURI().endsWith("/oauth2/revoke")
                && StringUtils.hasText(request.getParameter(OAuth2ParameterNames.TOKEN));
        if (!refresh && !revocation) {
            return null;
        }
        String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null || clientIds.length != 1 || !StringUtils.hasText(clientIds[0])) {
            return null;
        }
        return new OAuth2ClientAuthenticationToken(
                clientIds[0],
                ClientAuthenticationMethod.NONE,
                null,
                refresh
                        ? Map.of(
                        OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.REFRESH_TOKEN.getValue(),
                        PUBLIC_OPERATION, REFRESH)
                        : Map.of(PUBLIC_OPERATION, REVOCATION));
    }
}
