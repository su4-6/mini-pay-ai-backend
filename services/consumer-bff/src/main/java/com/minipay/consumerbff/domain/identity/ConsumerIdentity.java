package com.minipay.consumerbff.domain.identity;

/**
 * Outcome of a successful consumer code exchange. Mirrors the consumer claims Identity emits into
 * the access token so the BFF can answer {@code GET /api/v1/session} without decoding the JWT.
 */
public record ConsumerIdentity(
        String userId,
        String maskedPhone,
        String displayName,
        boolean payPasswordSet,
        boolean onboardingRequired,
        String realNameStatus,
        boolean realNameVerified) {

    public ConsumerIdentity {
        realNameStatus = realNameStatus == null || realNameStatus.isBlank()
                ? "UNVERIFIED" : realNameStatus;
        displayName = displayName == null || displayName.isBlank() ? "米灵用户" : displayName;
        maskedPhone = maskedPhone == null ? "" : maskedPhone;
    }
}
