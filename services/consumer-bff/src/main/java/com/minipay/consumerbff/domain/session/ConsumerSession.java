package com.minipay.consumerbff.domain.session;

import java.util.UUID;

/**
 * BFF-owned view of the authenticated consumer. Contains no credentials: access and refresh
 * tokens live in {@link ConsumerTokens} and are never serialized into an HTTP response body.
 */
public record ConsumerSession(
        UUID consumerId,
        String maskedPhone,
        String displayName,
        boolean payPasswordSet,
        boolean onboardingRequired,
        String realNameStatus,
        boolean realNameVerified) {
}
