package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record RechargeOrder(
        UUID rechargeId,
        String rechargeNo,
        UUID bankCardId,
        long amountCent,
        String channel,
        String status,
        String failureCode,
        Instant updatedAt) {
}
