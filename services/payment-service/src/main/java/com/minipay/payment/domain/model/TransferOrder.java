package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record TransferOrder(
        UUID transferId,
        UUID intentId,
        UUID receiverUserId,
        long amountCent,
        String status,
        String failureCode,
        Instant updatedAt) {
}
