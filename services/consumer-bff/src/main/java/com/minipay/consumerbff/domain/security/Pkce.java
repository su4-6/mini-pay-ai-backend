package com.minipay.consumerbff.domain.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * OAuth 2.1 PKCE (RFC 7636) S256 material. The verifier never leaves the BFF server side and is
 * discarded as soon as the authorization code has been exchanged.
 */
public record Pkce(String verifier, String challenge) {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static Pkce generate() {
        byte[] bytes = new byte[64];
        RANDOM.nextBytes(bytes);
        String verifier = URL_ENCODER.encodeToString(bytes);
        return new Pkce(verifier, challengeOf(verifier));
    }

    public static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
