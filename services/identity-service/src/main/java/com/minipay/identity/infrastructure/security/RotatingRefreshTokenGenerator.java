package com.minipay.identity.infrastructure.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Issues rotated refresh tokens to PKCE-bound native public clients as well as
 * confidential clients. Spring's default generator deliberately suppresses
 * refresh tokens for public authorization-code clients.
 */
public final class RotatingRefreshTokenGenerator
        implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    private final SecureRandom random = new SecureRandom();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        byte[] bytes = new byte[96];
        random.nextBytes(bytes);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(
                context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                issuedAt,
                expiresAt);
    }
}
