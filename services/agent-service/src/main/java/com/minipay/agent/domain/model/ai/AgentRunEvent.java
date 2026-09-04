package com.minipay.agent.domain.model.ai;

import java.time.Instant;
import java.util.UUID;

public record AgentRunEvent(
        UUID id,
        UUID runId,
        long sequenceNo,
        String eventType,
        int payloadVersion,
        String payload,
        Instant occurredAt,
        Instant expiresAt) {
}
