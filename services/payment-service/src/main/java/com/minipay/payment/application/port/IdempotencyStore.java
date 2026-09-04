package com.minipay.payment.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {
    Claim claim(
            UUID recordId,
            String actorId,
            String operation,
            String key,
            String requestDigest,
            Instant createdAt);

    void complete(
            String actorId,
            String operation,
            String key,
            int responseStatus,
            String responseJson,
            Instant completedAt);

    record Claim(boolean created, String requestDigest, Integer responseStatus,
                 Optional<String> responseJson) {
    }
}
