package com.minipay.commerce.domain.model;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAddress(
        UUID id,
        String label,
        String maskedSummary,
        String zoneCode,
        boolean defaultAddress,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
