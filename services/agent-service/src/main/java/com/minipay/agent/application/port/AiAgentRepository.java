package com.minipay.agent.application.port;

import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunEvent;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import com.minipay.agent.domain.model.ai.AiConversation;
import com.minipay.agent.domain.model.ai.AiMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiAgentRepository {
    void insertConversation(AiConversation conversation);

    Optional<AiConversation> findConversation(UUID userId, UUID conversationId);

    List<AiConversation> listConversations(UUID userId, Instant beforeTime, UUID beforeId, int limit);

    boolean renameConversation(UUID userId, UUID conversationId, String title, long expectedVersion, Instant now);

    boolean assignInitialTitle(UUID userId, UUID conversationId, String title, Instant now);

    boolean softDeleteConversation(UUID userId, UUID conversationId, Instant now);

    long reserveMessageSequence(UUID userId, UUID conversationId, Instant now);

    void insertMessage(AiMessage message);

    List<AiMessage> listMessages(UUID userId, UUID conversationId, Long beforeSequence, int limit);

    void insertRun(AgentRun run);

    Optional<AgentRun> findRun(UUID userId, UUID runId);

    Optional<AgentRun> findRunByIdempotencyHash(UUID userId, UUID conversationId, byte[] idempotencyHash);

    void lockUserRunGate(UUID userId, Instant now);

    Optional<AiConversation> lockConversation(UUID userId, UUID conversationId);

    int countActiveRuns(UUID userId, UUID excludedRunId);

    boolean hasActiveRun(UUID userId, UUID conversationId, UUID excludedRunId);

    List<AgentRun> listActiveRuns(UUID userId, int limit);

    List<AgentRun> listStaleActiveRuns(Instant updatedBefore, int limit);

    boolean transitionRun(UUID userId, UUID runId, AgentRunStatus source, AgentRunStatus target,
                          long expectedVersion, Instant startedAt, Instant completedAt, Instant now);

    boolean requestCancellation(UUID userId, UUID runId, Instant now);

    long reserveEventSequence(UUID runId, Instant now);

    void insertRunEvent(AgentRunEvent event);

    List<AgentRunEvent> listRunEvents(UUID userId, UUID runId, long afterSequence, int limit);

    void insertToolTrace(
            UUID traceId, UUID runId, String toolName, String riskLevel,
            int schemaVersion, byte[] requestDigest, Instant now);

    boolean completeToolTrace(
            UUID traceId, byte[] resultDigest, String resultCode,
            long latencyMs, Instant completedAt);

    int completeOpenToolTraces(UUID runId, byte[] resultDigest, String resultCode, Instant completedAt);
}
