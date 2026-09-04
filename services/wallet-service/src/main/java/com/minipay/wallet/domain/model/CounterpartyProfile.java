package com.minipay.wallet.domain.model;

import java.time.Instant;
import java.util.UUID;

public record CounterpartyProfile(
        UUID userId,
        String nickname,
        String avatarUrl,
        Instant avatarUrlExpiresAt,
        String legalNameMasked) {
}
