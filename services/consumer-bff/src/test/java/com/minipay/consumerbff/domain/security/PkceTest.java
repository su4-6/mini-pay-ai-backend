package com.minipay.consumerbff.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PkceTest {

    @Test
    void generatesRfc7636S256ChallengeFromAnUrlSafeVerifier() {
        Pkce pkce = Pkce.generate();

        assertThat(pkce.verifier()).matches("^[A-Za-z0-9_-]{43,128}$");
        assertThat(pkce.challenge()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(Pkce.challengeOf(pkce.verifier())).isEqualTo(pkce.challenge());
    }

    @Test
    void challengeMatchesTheDocumentedHashOfTheVerifier() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        assertThat(Pkce.challengeOf(verifier)).isEqualTo(expected);
    }

    @Test
    void generatesADistinctVerifierPerLoginAttempt() {
        assertThat(Pkce.generate().verifier()).isNotEqualTo(Pkce.generate().verifier());
    }
}
