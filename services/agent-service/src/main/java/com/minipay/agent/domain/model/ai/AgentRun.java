package com.minipay.agent.domain.model.ai;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(
        UUID id,
        UUID conversationId,
        UUID userId,
        UUID clientMessageId,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        long contextVersion,
        AgentRunStatus status,
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
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {
}
