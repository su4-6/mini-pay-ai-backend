package com.minipay.agent.application.port;

import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryRepository {
    Optional<MemorySetting> findSetting(UUID userId);

    MemorySetting saveSetting(MemorySetting setting, long expectedVersion, Instant now);

    boolean isOwnedUserMessage(UUID userId, UUID messageId);

    List<MemoryItem> listItems(UUID userId, MemoryType type, int limit);

    long countItems(UUID userId, MemoryType type);

    Optional<MemoryItem> findByManualIdempotencyHash(UUID userId, byte[] idempotencyHash);

    Optional<MemoryItem> findItem(UUID userId, UUID itemId);

    MemoryItem insertItem(MemoryItem item);

    boolean updateItem(UUID userId, UUID itemId, String displayValue, String referenceType,
                       String referenceId, long expectedVersion, Instant now);

    boolean deleteItem(UUID userId, UUID itemId, long expectedVersion, Instant now);
}
