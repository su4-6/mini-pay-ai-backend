package com.minipay.wallet.application.service;

import java.time.Instant;
import java.util.UUID;

/** A post-commit UI notification. The wallet bill API remains the source of truth. */
public record CollectionReceiptReady(
        UUID eventId,
        UUID ownerId,
        UUID billId,
        String source,
        long amountCent,
        String counterpartyDisplay,
        Instant occurredAt) {
}
