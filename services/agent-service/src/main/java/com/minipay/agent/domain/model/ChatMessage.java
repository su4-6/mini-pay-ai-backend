package com.minipay.agent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(
        Long id,
        String conversationId,
        UUID senderId,
        String senderType,
        String content,
        String messageType,
        String transferAmount,
        String transferStatus,
        String transferDirection,
        UUID transferId,
        UUID transferTargetUserId,
        UUID voiceMediaId,
        Integer voiceDurationMs,
        UUID mediaId,
        String mediaKind,
        String mediaContentType,
        Integer mediaWidth,
        Integer mediaHeight,
        Integer mediaDurationMs,
        UUID callId,
        String callStatus,
        Integer callDurationSeconds,
        Instant createdAt) {
    public ChatMessage(Long id, String conversationId, UUID senderId, String senderType,
            String content, String messageType, String transferAmount, String transferStatus,
            String transferDirection, Instant createdAt) {
        this(id, conversationId, senderId, senderType, content, messageType,
                transferAmount, transferStatus, transferDirection,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, createdAt);
    }
}
