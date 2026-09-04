package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BankCard(
        UUID cardId,
        String bankName,
        String cardType,
        String maskedCardNo,
        String status,
        Instant verifiedAt) {
}
