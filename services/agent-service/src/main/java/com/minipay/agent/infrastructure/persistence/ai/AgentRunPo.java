package com.minipay.agent.infrastructure.persistence.ai;

import java.time.LocalDateTime;

public record AgentRunPo(
        byte[] id,
        byte[] conversationId,
        byte[] userId,
        byte[] clientMessageId,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        long contextVersion,
        String status,
        String intentType,
        String businessRefType,
        String businessRefId,
        String modelProvider,
        String modelName,
        String promptVersion,
        String toolCatalogVersion,
        long nextEventSequence,
        boolean cancelRequested,
        long version,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {
}
