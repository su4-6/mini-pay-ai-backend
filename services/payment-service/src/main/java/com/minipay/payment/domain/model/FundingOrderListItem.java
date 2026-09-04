package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record FundingOrderListItem(
        UUID applicationId,
        String businessNo,
        UUID bankCardId,
        String bankName,
        String maskedCardNo,
        long amountCent,
        String status,
        String failureCode,
        Instant createdAt,
        Instant updatedAt) {
}
