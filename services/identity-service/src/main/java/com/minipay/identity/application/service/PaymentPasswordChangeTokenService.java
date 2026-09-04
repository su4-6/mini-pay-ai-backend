package com.minipay.identity.application.service;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.stereotype.Service;

@Service
public class PaymentPasswordChangeTokenService {
    private static final String AUDIENCE = "minipay-account-security";
    private static final String PURPOSE = "PAYMENT_PASSWORD_CHANGE";
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;

    public PaymentPasswordChangeTokenService(
            JWKSource<SecurityContext> jwkSource,
            @Value("${minipay.identity.issuer}") String issuer) {
        this.encoder = new NimbusJwtEncoder(jwkSource);
        NimbusJwtDecoder signatureDecoder = (NimbusJwtDecoder)
                OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        signatureDecoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        this.decoder = signatureDecoder;
        this.issuer = issuer;
    }

    public String issue(
            UUID verificationId,
            UUID userId,
            String deviceId,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(AUDIENCE))
                .subject(userId.toString())
                .id(verificationId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("purpose", PURPOSE)
                .claim("device_id", deviceId)
                .build();
        JwsHeader header = JwsHeader.with(() -> "RS256")
                .type("JWT")
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Claims verify(String token) {
        try {
            Jwt jwt = decoder.decode(token == null ? "" : token);
            if (!issuer.equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                    || !jwt.getAudience().contains(AUDIENCE)
                    || !PURPOSE.equals(jwt.getClaimAsString("purpose"))) {
                throw invalid();
            }
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
                throw new AccountSecurityRejectedException("VERIFICATION_TOKEN_EXPIRED");
            }
            return new Claims(
                    UUID.fromString(jwt.getId()),
                    UUID.fromString(jwt.getSubject()),
                    jwt.getClaimAsString("device_id"),
                    expiresAt);
        } catch (AccountSecurityRejectedException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private AccountSecurityRejectedException invalid() {
        return new AccountSecurityRejectedException("VERIFICATION_TOKEN_INVALID");
    }

    public record Claims(UUID verificationId, UUID userId, String deviceId, Instant expiresAt) {
    }
}
