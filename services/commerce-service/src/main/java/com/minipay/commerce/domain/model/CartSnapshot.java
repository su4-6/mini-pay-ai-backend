package com.minipay.commerce.domain.model;

import java.util.List;
import java.util.UUID;

public record CartSnapshot(
        UUID cartId,
        UUID userId,
        UUID merchantId,
        String merchantName,
        long version,
        List<Item> items,
        long itemAmountCent) {
    public CartSnapshot {
        items = List.copyOf(items);
        if (itemAmountCent != items.stream().mapToLong(Item::lineAmountCent).sum()) {
            throw new IllegalArgumentException("Cart amount is inconsistent");
        }
    }

    public record Item(
            UUID cartItemId,
            UUID skuId,
            String itemName,
            String skuName,
            String optionSummary,
            long unitPriceCent,
            int quantity,
            long lineAmountCent) {
    }
}
