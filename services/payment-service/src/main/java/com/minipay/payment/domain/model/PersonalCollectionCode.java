package com.minipay.payment.domain.model;

import java.time.Instant;

public record PersonalCollectionCode(
        String type,
        String deepLink,
        Instant expiresAt,
        String sandboxNotice) {
}
