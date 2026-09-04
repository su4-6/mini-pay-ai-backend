package com.minipay.agent.domain.model.ai;

import java.time.Instant;
import java.util.UUID;

public record MemoryItem(
        UUID id,
        UUID userId,
        MemoryType type,
        String displayValue,
        String referenceType,
        String referenceId,
        String status,
        UUID consentMessageId,
        String consentSource,
        byte[] manualIdempotencyHash,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
