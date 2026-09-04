package com.minipay.agent.infrastructure.persistence.ai;

import java.time.LocalDateTime;

public record AiConversationPo(
        byte[] id,
        byte[] userId,
        String title,
        String status,
        long version,
        long nextMessageSequence,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt) {
}
