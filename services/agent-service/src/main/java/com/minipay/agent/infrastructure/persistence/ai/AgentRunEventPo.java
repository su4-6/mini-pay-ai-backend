package com.minipay.agent.infrastructure.persistence.ai;

import java.time.LocalDateTime;

public record AgentRunEventPo(
        byte[] id,
        byte[] runId,
        long sequenceNo,
        String eventType,
        int payloadVersion,
        String payload,
        LocalDateTime occurredAt,
        LocalDateTime expiresAt) {
}
