package com.minipay.identity.domain.model;

import java.util.UUID;

public record ConsumerPrincipal(
        UUID userId,
        String displayName,
        boolean payPasswordSet,
        boolean onboardingCompleted,
        String realNameStatus,
        boolean merchantOwner) {
    public ConsumerPrincipal(UUID userId, String displayName, boolean payPasswordSet) {
        this(userId, displayName, payPasswordSet, payPasswordSet, "UNVERIFIED", false);
    }

    public boolean realNameVerified() {
        return "VERIFIED".equals(realNameStatus);
    }
}
