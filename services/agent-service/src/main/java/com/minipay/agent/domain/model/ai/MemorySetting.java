package com.minipay.agent.domain.model.ai;

import java.time.Instant;
import java.util.UUID;

public record MemorySetting(
        UUID userId,
        boolean enabled,
        boolean foodPreferenceEnabled,
        boolean allergenAvoidanceEnabled,
        boolean mealBudgetEnabled,
        boolean contactAliasEnabled,
        boolean addressAliasEnabled,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static MemorySetting disabled(UUID userId, Instant now) {
        return new MemorySetting(userId, false, false, false, false, false, false, 0, now, now);
    }

    public boolean permits(MemoryType type) {
        if (!enabled) return false;
        return switch (type) {
            case CUSTOM -> true;
            case FOOD_PREFERENCE -> foodPreferenceEnabled;
            case ALLERGEN_AVOIDANCE -> allergenAvoidanceEnabled;
            case MEAL_BUDGET -> mealBudgetEnabled;
            case CONTACT_ALIAS -> contactAliasEnabled;
            case ADDRESS_ALIAS -> addressAliasEnabled;
        };
    }
}
