package com.minipay.agent.domain.model.ai;

import java.time.Instant;
import java.util.UUID;

public record AiMessage(
        UUID id,
        UUID conversationId,
        UUID runId,
        Role role,
        String contentText,
        String cardType,
        Integer cardVersion,
        String cardPayload,
        long sequenceNo,
        Instant createdAt) {

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }
}
