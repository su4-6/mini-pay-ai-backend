package com.minipay.consumerbff.domain.session;

/**
 * Server-side OAuth credentials of one browser session. Only ever held in the Redis WebSession;
 * accessors used by outbound calls must never be logged or echoed.
 */
public record ConsumerTokens(String accessToken, String refreshToken) {

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /** Redacted representation so accidental logging or exception messages leak no token material. */
    @Override
    public String toString() {
        return "ConsumerTokens[accessToken=***, refreshToken="
                + (hasRefreshToken() ? "***" : "absent") + "]";
    }
}
