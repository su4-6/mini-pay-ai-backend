package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BankTransaction(
        UUID transactionId,
        String transactionType,
        String direction,
        String description,
        long amountCent,
        String status,
        Instant occurredAt) {
}
