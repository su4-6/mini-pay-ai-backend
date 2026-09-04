package com.minipay.commerce.domain.model;

import java.util.List;
import java.util.UUID;

public final class CatalogView {
    private CatalogView() {
    }

    public record Merchant(
            UUID id,
            String name,
            String categoryCode,
            long minimumOrderCent,
            long deliveryFeeCent,
            int estimatedDeliveryMinutes) {
    }

    public record MenuItem(
            UUID itemId,
            String name,
            String description,
            String tasteTags,
            String allergenTags,
            List<Sku> skus) {
        public MenuItem {
            skus = List.copyOf(skus);
        }
    }

    public record Sku(UUID skuId, String name, long priceCent, int availableQuantity, long version) {
    }
}
