package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentPasswordChangeTokenServiceTest {
    private final PaymentPasswordChangeTokenService tokens =
            new PaymentPasswordChangeTokenService(jwkSource(), "https://identity.test");

    @Test
    void signsAndReadsABoundShortLivedCredential() {
        UUID verificationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(300);

        String token = tokens.issue(
                verificationId, userId, "device-1", issuedAt, expiresAt);
        PaymentPasswordChangeTokenService.Claims claims = tokens.verify(token);

        assertThat(claims.verificationId()).isEqualTo(verificationId);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.deviceId()).isEqualTo("device-1");
        assertThat(claims.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void rejectsTamperingAndExpiredCredentialsWithStableCodes() {
        UUID verificationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(600);
        String expired = tokens.issue(
                verificationId, userId, "device-1", issuedAt, issuedAt.plusSeconds(300));
        String valid = tokens.issue(
                verificationId, userId, "device-1", Instant.now(), Instant.now().plusSeconds(300));
        String[] segments = valid.split("\\.");
        String signature = segments[2];
        String tampered = segments[0] + "." + segments[1] + "."
                + (signature.startsWith("a") ? "b" : "a") + signature.substring(1);

        assertThatThrownBy(() -> tokens.verify(expired))
                .isInstanceOf(AccountSecurityRejectedException.class)
                .extracting("code")
                .isEqualTo("VERIFICATION_TOKEN_EXPIRED");
        assertThatThrownBy(() -> tokens.verify(tampered))
                .isInstanceOf(AccountSecurityRejectedException.class)
                .extracting("code")
                .isEqualTo("VERIFICATION_TOKEN_INVALID");
    }

    private static JWKSource<SecurityContext> jwkSource() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("test-key")
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsa));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
