package com.minipay.payment.domain.model;

import java.util.List;

public record BankTransactionPage(
        List<BankTransaction> items,
        int page,
        int size,
        long total) {
}
