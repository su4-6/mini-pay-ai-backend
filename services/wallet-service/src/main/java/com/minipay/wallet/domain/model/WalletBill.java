package com.minipay.wallet.domain.model;

import java.time.Instant;
import java.util.UUID;

public record WalletBill(
        UUID billId,
        String businessType,
        String businessNo,
        String source,
        String direction,
        long amountCent,
        String counterpartyDisplay,
        UUID counterpartyUserId,
        CounterpartyProfile counterpartyProfile,
        String remark,
        String status,
        Long balanceAfterCent,
        String failureCode,
        Instant occurredAt,
        Instant updatedAt) {
}
