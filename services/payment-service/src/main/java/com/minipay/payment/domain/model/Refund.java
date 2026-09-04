package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Refund(
        UUID refundId,
        UUID paymentOrderId,
        long amountCent,
        String status,
        String failureCode,
        Instant updatedAt) {
}
