package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PaymentOrder(
        UUID paymentOrderId,
        String paymentOrderNo,
        long amountCent,
        String currency,
        String subject,
        String paymentMethod,
        String status,
        String redirectUrl,
        String failureCode,
        Instant expiresAt,
        Instant updatedAt) {
}
