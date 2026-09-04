package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BankBalance(
        UUID cardId,
        long availableAmountCent,
        String currency,
        Instant asOf,
        String sandboxNotice) {
    public BankBalance forCard(UUID value) {
        return new BankBalance(
                value, availableAmountCent, currency, asOf, sandboxNotice);
    }
}
