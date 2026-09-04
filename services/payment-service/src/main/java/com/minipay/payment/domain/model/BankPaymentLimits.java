package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BankPaymentLimits(
        UUID cardId,
        long singlePaymentLimitCent,
        long dailyPaymentLimitCent,
        long dailyUsedCent,
        long dailyRemainingCent,
        String currency,
        Instant asOf) {
    public BankPaymentLimits forCard(UUID value) {
        return new BankPaymentLimits(
                value,
                singlePaymentLimitCent,
                dailyPaymentLimitCent,
                dailyUsedCent,
                dailyRemainingCent,
                currency,
                asOf);
    }
}
