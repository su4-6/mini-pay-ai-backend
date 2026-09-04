package com.minipay.agent.domain.model.ai;

import java.time.Instant;
import java.util.UUID;

public record AiConversation(
        UUID id,
        UUID userId,
        String title,
        String status,
        long version,
        long nextMessageSequence,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {
}
