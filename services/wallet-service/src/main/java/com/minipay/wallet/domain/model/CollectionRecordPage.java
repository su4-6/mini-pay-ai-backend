package com.minipay.wallet.domain.model;

import java.time.Instant;
import java.util.List;

public record CollectionRecordPage(
        List<WalletBill> items, int page, int size, long total,
        String type, String period, Instant from, Instant to, Summary summary) {
    public record Summary(
            long collectionCount, long collectionAmountCent,
            long refundCount, long refundAmountCent, long netAmountCent) {
    }
}
