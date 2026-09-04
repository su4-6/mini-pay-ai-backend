package com.minipay.agent.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AgentTaskStateStore {
    Optional<PendingTask> findLatest(UUID userId, UUID conversationId, Instant notBefore);
    Optional<PendingTask> findForRun(UUID userId, UUID runId, String taskType);
    void save(UUID runId, String taskType, Map<String, Object> slots, Instant now);
    void clear(UUID userId, UUID conversationId, String taskType);

    record PendingTask(UUID runId, String taskType, Map<String, Object> slots) {}
}
