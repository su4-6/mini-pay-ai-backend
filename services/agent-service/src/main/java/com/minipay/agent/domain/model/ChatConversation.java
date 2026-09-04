package com.minipay.agent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ChatConversation(
        String id,
        UUID userId,
        String contactId,
        String name,
        String lastMessage,
        long lastMessageTime,
        int unreadCount,
        boolean isTransfer,
        int avatarColorIndex,
        Instant createdAt,
        Instant updatedAt) {
}
