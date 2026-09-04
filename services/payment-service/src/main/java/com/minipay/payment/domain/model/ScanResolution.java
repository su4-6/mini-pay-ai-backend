package com.minipay.payment.domain.model;

import java.util.UUID;

public record ScanResolution(
        String type,
        UUID receiverUserId,
        String receiverDisplay,
        String receiverNickname,
        String receiverAvatarUrl,
        String receiverLegalNameMasked) {
}
