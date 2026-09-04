package com.minipay.identity.domain.model;

import java.util.UUID;

public record ConsumerProfile(
        UUID userId,
        String nickname,
        String avatarObjectKey,
        boolean payPasswordSet,
        boolean onboardingCompleted) {
}
