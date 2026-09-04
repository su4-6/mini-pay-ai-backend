package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record TransferIntent(
        UUID intentId,
        long amountCent,
        String status,
        Instant expiresAt) {
}
