package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record WithdrawalOrder(
        UUID withdrawalId,
        String withdrawalNo,
        UUID bankCardId,
        long amountCent,
        String status,
        String failureCode,
        Instant updatedAt) {
}
