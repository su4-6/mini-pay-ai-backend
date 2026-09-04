package com.minipay.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.BiFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.annotation.Transactional;

public class DigestingOAuth2AuthorizationService implements OAuth2AuthorizationService {
    private static final String PREFIX = "hmac-sha256:";
    private final OAuth2AuthorizationService delegate;
    private final JdbcTemplate jdbcTemplate;
    private final byte[] pepper;

    public DigestingOAuth2AuthorizationService(
            OAuth2AuthorizationService delegate,
            JdbcTemplate jdbcTemplate,
            String pepper) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public void save(OAuth2Authorization authorization) {
        OAuth2Authorization digested = transformAll(authorization, this::digestToken);
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh =
                digested.getToken(OAuth2RefreshToken.class);
        if (refresh != null) {
            ensureFamily(authorization.getId());
            String familyStatus = lockFamily(authorization.getId());
            if ("REVOKED".equals(familyStatus)) {
                delegate.remove(digested);
                throw new IllegalStateException("Refresh token family has been revoked");
            }
        }
        delegate.save(digested);
        if (refresh != null) {
            boolean invalidated = Boolean.TRUE.equals(
                    refresh.getMetadata(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME));
            if (invalidated) {
                jdbcTemplate.update("""
                        INSERT INTO oauth2_refresh_token_history (
                          token_digest, authorization_id, active, created_at, revoked_at
                        ) VALUES (?, ?, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE
                          authorization_id = VALUES(authorization_id),
                          active = FALSE,
                          revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP(6))
                        """,
                        refresh.getToken().getTokenValue(),
                        authorization.getId());
                revokeFamily(authorization.getId(), "TOKEN_REVOCATION");
            } else {
                jdbcTemplate.update("""
                        INSERT INTO oauth2_refresh_token_history (
                          token_digest, authorization_id, active, created_at
                        ) VALUES (?, ?, TRUE, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE authorization_id = VALUES(authorization_id)
                        """,
                        refresh.getToken().getTokenValue(),
                        authorization.getId());
            }
        }
    }

    @Override
    @Transactional
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
        jdbcTemplate.update("""
                UPDATE oauth2_refresh_token_history
                SET active = FALSE, revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP(6))
                WHERE authorization_id = ?
                """, authorization.getId());
        ensureFamily(authorization.getId());
        revokeFamily(authorization.getId(), "AUTHORIZATION_REMOVED");
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    @Transactional
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        String digest = digest(token);
        if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            String authorizationId = findAuthorizationId(digest);
            if (authorizationId == null) {
                return null;
            }
            ensureFamily(authorizationId);
            if ("REVOKED".equals(lockFamily(authorizationId))) {
                return null;
            }
            int claimed = jdbcTemplate.update("""
                    UPDATE oauth2_refresh_token_history
                    SET active = FALSE, consumed_at = UTC_TIMESTAMP(6)
                    WHERE token_digest = ? AND active = TRUE AND revoked_at IS NULL
                    """, digest);
            if (claimed == 0) {
                if (wasConsumed(digest)) {
                    revokeFamily(authorizationId, "TOKEN_REUSE");
                    OAuth2Authorization family = delegate.findById(authorizationId);
                    if (family != null) {
                        delegate.remove(family);
                    }
                    jdbcTemplate.update("""
                            UPDATE oauth2_refresh_token_history
                            SET active = FALSE, revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP(6))
                            WHERE authorization_id = ?
                            """, authorizationId);
                }
                return null;
            }
        }
        OAuth2Authorization authorization = delegate.findByToken(digest, tokenType);
        if (authorization == null) {
            return null;
        }
        return transformMatchingDigest(authorization, digest, token);
    }

    private String findAuthorizationId(String digest) {
        return jdbcTemplate.query(
                """
                SELECT authorization_id
                FROM oauth2_refresh_token_history
                WHERE token_digest = ?
                """,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                digest);
    }

    private boolean wasConsumed(String digest) {
        Boolean consumed = jdbcTemplate.query(
                """
                SELECT consumed_at IS NOT NULL
                FROM oauth2_refresh_token_history
                WHERE token_digest = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next() && resultSet.getBoolean(1),
                digest);
        return Boolean.TRUE.equals(consumed);
    }

    private void ensureFamily(String authorizationId) {
        jdbcTemplate.update("""
                INSERT INTO oauth2_refresh_token_family (
                  authorization_id, status, created_at, updated_at
                ) VALUES (?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE authorization_id = VALUES(authorization_id)
                """, authorizationId);
    }

    private String lockFamily(String authorizationId) {
        return jdbcTemplate.query(
                """
                SELECT status
                FROM oauth2_refresh_token_family
                WHERE authorization_id = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                authorizationId);
    }

    private void revokeFamily(String authorizationId, String reason) {
        jdbcTemplate.update("""
                UPDATE oauth2_refresh_token_family
                SET status = 'REVOKED',
                    revoke_reason = COALESCE(revoke_reason, ?),
                    updated_at = UTC_TIMESTAMP(6)
                WHERE authorization_id = ?
                """, reason, authorizationId);
    }

    private OAuth2Authorization transformAll(
            OAuth2Authorization authorization,
            BiFunction<String, OAuth2Token, OAuth2Token> transformer) {
        OAuth2Authorization result = authorization;
        result = replace(result, OAuth2AuthorizationCode.class, transformer);
        result = replace(result, OAuth2AccessToken.class, transformer);
        result = replace(result, OAuth2RefreshToken.class, transformer);
        result = replace(result, OidcIdToken.class, transformer);
        return result;
    }

    private OAuth2Authorization transformMatchingDigest(
            OAuth2Authorization authorization,
            String expectedDigest,
            String rawToken) {
        return transformAll(authorization, (value, token) ->
                value.equals(expectedDigest) ? recreate(token, rawToken) : token);
    }

    private OAuth2Token digestToken(String value, OAuth2Token token) {
        return value.startsWith(PREFIX) ? token : recreate(token, digest(value));
    }

    private <T extends OAuth2Token> OAuth2Authorization replace(
            OAuth2Authorization authorization,
            Class<T> tokenClass,
            BiFunction<String, OAuth2Token, OAuth2Token> transformer) {
        OAuth2Authorization.Token<T> holder = authorization.getToken(tokenClass);
        if (holder == null) {
            return authorization;
        }
        OAuth2Token replacement = transformer.apply(holder.getToken().getTokenValue(), holder.getToken());
        if (replacement == holder.getToken()) {
            return authorization;
        }
        return OAuth2Authorization.from(authorization)
                .token(replacement, metadata -> metadata.putAll(holder.getMetadata()))
                .build();
    }

    private OAuth2Token recreate(OAuth2Token token, String value) {
        if (token instanceof OAuth2AuthorizationCode code) {
            return new OAuth2AuthorizationCode(value, code.getIssuedAt(), code.getExpiresAt());
        }
        if (token instanceof OAuth2AccessToken access) {
            return new OAuth2AccessToken(
                    access.getTokenType(), value, access.getIssuedAt(), access.getExpiresAt(), access.getScopes());
        }
        if (token instanceof OAuth2RefreshToken refresh) {
            return new OAuth2RefreshToken(value, refresh.getIssuedAt(), refresh.getExpiresAt());
        }
        if (token instanceof OidcIdToken idToken) {
            return new OidcIdToken(value, idToken.getIssuedAt(), idToken.getExpiresAt(), idToken.getClaims());
        }
        throw new IllegalArgumentException("Unsupported OAuth token type: " + token.getClass().getName());
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to digest OAuth token", exception);
        }
    }
}
