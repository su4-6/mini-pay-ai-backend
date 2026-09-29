package com.minipay.payment.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.payment.application.service.PaymentProblemException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ConsumerClaimsTest {
    private static final UUID USER_ID =
            UUID.fromString("019d0000-0000-7000-8000-000000000001");

    @Test
    void onboardedUserCanOwnACollectionCodeBeforeRealNameVerification() {
        Jwt jwt = token(false);

        assertThat(ConsumerClaims.requireOnboardedUser(jwt)).isEqualTo(USER_ID);
        assertThatThrownBy(() -> ConsumerClaims.requireReadyUser(jwt, false))
                .isInstanceOfSatisfying(PaymentProblemException.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo("REAL_NAME_VERIFICATION_REQUIRED"));
    }

    private static Jwt token(boolean realNameVerified) {
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .issuedAt(Instant.parse("2026-09-29T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-29T01:00:00Z"))
                .claim("onboarding_completed", true)
                .claim("real_name_verified", realNameVerified)
                .claim("real_name_status", "UNVERIFIED")
                .build();
    }
}
