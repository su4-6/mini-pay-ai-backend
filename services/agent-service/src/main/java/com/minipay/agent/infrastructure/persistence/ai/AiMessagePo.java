package com.minipay.agent.infrastructure.persistence.ai;

import java.time.LocalDateTime;

public record AiMessagePo(
        byte[] id,
        byte[] conversationId,
        byte[] runId,
        String role,
        String contentText,
        String cardType,
        Integer cardVersion,
        String cardPayload,
        long sequenceNo,
        LocalDateTime createdAt) {
}
