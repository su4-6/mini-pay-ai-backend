package com.minipay.wallet.domain.model;

import java.time.Instant;

public record BillQuery(
        Instant from,
        Instant to,
        String direction,
        String businessType,
        String source,
        String status,
        int page,
        int size) {
    public BillQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }
}
