package com.minipay.commerce.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutQuote(
        UUID id,
        UUID userId,
        UUID cartId,
        UUID merchantId,
        UUID addressId,
        long cartVersion,
        long itemAmountCent,
        long deliveryFeeCent,
        long discountCent,
        long payableAmountCent,
        Instant expiresAt,
        List<QuoteItem> items) {
    public CheckoutQuote {
        items = List.copyOf(items);
        long itemTotal = items.stream().mapToLong(QuoteItem::lineAmountCent).sum();
        if (items.isEmpty() || itemAmountCent != itemTotal || payableAmountCent <= 0
                || payableAmountCent != itemAmountCent + deliveryFeeCent - discountCent) {
            throw new IllegalArgumentException("Quote amounts are inconsistent");
        }
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public record QuoteItem(
            UUID skuId,
            String itemName,
            String skuName,
            String optionSummary,
            long unitPriceCent,
            int quantity,
            long lineAmountCent,
            long inventoryVersion) {
        public QuoteItem {
            if (unitPriceCent <= 0 || quantity <= 0
                    || Math.multiplyExact(unitPriceCent, quantity) != lineAmountCent) {
                throw new IllegalArgumentException("Quote item amount is inconsistent");
            }
        }
    }
}
