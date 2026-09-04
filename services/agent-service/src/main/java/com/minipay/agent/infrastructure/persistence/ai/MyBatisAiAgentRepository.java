package com.minipay.agent.infrastructure.persistence.ai;

import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunEvent;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import com.minipay.agent.domain.model.ai.AiConversation;
import com.minipay.agent.domain.model.ai.AiMessage;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAiAgentRepository implements AiAgentRepository {
    private final AiAgentMapper mapper;

    public MyBatisAiAgentRepository(AiAgentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertConversation(AiConversation conversation) {
        requireOne(mapper.insertConversation(toPo(conversation)), "insert ai conversation");
    }

    @Override
    public Optional<AiConversation> findConversation(UUID userId, UUID conversationId) {
        return Optional.ofNullable(mapper.selectConversation(bytes(userId), bytes(conversationId)))
                .map(MyBatisAiAgentRepository::toDomain);
    }

    @Override
    public List<AiConversation> listConversations(UUID userId, Instant beforeTime, UUID beforeId, int limit) {
        return mapper.selectConversations(
                        bytes(userId), local(beforeTime), beforeId == null ? null : bytes(beforeId), limit)
                .stream()
                .map(MyBatisAiAgentRepository::toDomain)
                .toList();
    }

    @Override
    public boolean renameConversation(UUID userId, UUID conversationId, String title,
                                      long expectedVersion, Instant now) {
        return mapper.renameConversation(bytes(userId), bytes(conversationId), title,
                expectedVersion, local(now)) == 1;
    }

    @Override
    public boolean assignInitialTitle(UUID userId, UUID conversationId, String title, Instant now) {
        return mapper.assignInitialTitle(bytes(userId), bytes(conversationId), title, local(now)) == 1;
    }

    @Override
    public boolean softDeleteConversation(UUID userId, UUID conversationId, Instant now) {
        return mapper.softDeleteConversation(bytes(userId), bytes(conversationId), local(now)) == 1;
    }

    @Override
    public long reserveMessageSequence(UUID userId, UUID conversationId, Instant now) {
        AiConversationPo conversation = mapper.lockConversation(bytes(userId), bytes(conversationId));
        if (conversation == null) {
            throw new IllegalStateException("AI conversation is not available");
        }
        long sequence = conversation.nextMessageSequence();
        requireOne(mapper.updateConversationSequence(bytes(conversationId), sequence + 1, local(now)),
                "reserve ai message sequence");
        return sequence;
    }

    @Override
    public void insertMessage(AiMessage message) {
        requireOne(mapper.insertMessage(toPo(message)), "insert ai message");
    }

    @Override
    public List<AiMessage> listMessages(UUID userId, UUID conversationId, Long beforeSequence, int limit) {
        return mapper.selectMessages(bytes(userId), bytes(conversationId), beforeSequence, limit)
                .stream()
                .map(MyBatisAiAgentRepository::toDomain)
                .toList();
    }

    @Override
    public void insertRun(AgentRun run) {
        requireOne(mapper.insertRun(toPo(run)), "insert agent run");
    }

    @Override
    public Optional<AgentRun> findRun(UUID userId, UUID runId) {
        return Optional.ofNullable(mapper.selectRun(bytes(userId), bytes(runId)))
                .map(MyBatisAiAgentRepository::toDomain);
    }

    @Override
    public Optional<AgentRun> findRunByIdempotencyHash(
            UUID userId, UUID conversationId, byte[] idempotencyHash) {
        return Optional.ofNullable(mapper.selectRunByIdempotencyHash(
                        bytes(userId), bytes(conversationId), idempotencyHash))
                .map(MyBatisAiAgentRepository::toDomain);
    }

    @Override
    public void lockUserRunGate(UUID userId, Instant now) {
        mapper.insertUserRunGate(bytes(userId), local(now));
        if (mapper.lockUserRunGate(bytes(userId)) == null) {
            throw new IllegalStateException("Agent user run gate is not available");
        }
    }

    @Override
    public Optional<AiConversation> lockConversation(UUID userId, UUID conversationId) {
        return Optional.ofNullable(mapper.lockConversation(bytes(userId), bytes(conversationId)))
                .map(MyBatisAiAgentRepository::toDomain);
    }

    @Override
    public int countActiveRuns(UUID userId, UUID excludedRunId) {
        return mapper.countActiveRuns(bytes(userId), bytes(excludedRunId));
    }

    @Override
    public boolean hasActiveRun(UUID userId, UUID conversationId, UUID excludedRunId) {
        return mapper.countActiveConversationRuns(
                bytes(userId), bytes(conversationId), bytes(excludedRunId)) > 0;
    }

    @Override
    public List<AgentRun> listActiveRuns(UUID userId, int limit) {
        return mapper.selectActiveRuns(bytes(userId), limit).stream()
                .map(MyBatisAiAgentRepository::toDomain).toList();
    }

    @Override
    public List<AgentRun> listStaleActiveRuns(Instant updatedBefore, int limit) {
        return mapper.selectStaleActiveRuns(local(updatedBefore), limit).stream()
                .map(MyBatisAiAgentRepository::toDomain).toList();
    }

    @Override
    public boolean transitionRun(UUID userId, UUID runId, AgentRunStatus source, AgentRunStatus target,
                                 long expectedVersion, Instant startedAt, Instant completedAt, Instant now) {
        return mapper.transitionRun(bytes(userId), bytes(runId), source.name(), target.name(), expectedVersion,
                local(startedAt), local(completedAt), local(now)) == 1;
    }

    @Override
    public boolean requestCancellation(UUID userId, UUID runId, Instant now) {
        return mapper.requestCancellation(bytes(userId), bytes(runId), local(now)) == 1;
    }

    @Override
    public long reserveEventSequence(UUID runId, Instant now) {
        AgentRunPo run = mapper.lockRun(bytes(runId));
        if (run == null) {
            throw new IllegalStateException("Agent run is not available");
        }
        long sequence = run.nextEventSequence();
        requireOne(mapper.updateRunEventSequence(bytes(runId), sequence + 1, local(now)),
                "reserve agent event sequence");
        return sequence;
    }

    @Override
    public void insertRunEvent(AgentRunEvent event) {
        requireOne(mapper.insertRunEvent(toPo(event)), "insert agent run event");
    }

    @Override
    public List<AgentRunEvent> listRunEvents(UUID userId, UUID runId, long afterSequence, int limit) {
        return mapper.selectRunEvents(bytes(userId), bytes(runId), afterSequence,
                        LocalDateTime.now(ZoneOffset.UTC), limit)
                .stream()
                .map(MyBatisAiAgentRepository::toDomain)
                .toList();
    }

    @Override
    public void insertToolTrace(
            UUID traceId,
            UUID runId,
            String toolName,
            String riskLevel,
            int schemaVersion,
            byte[] requestDigest,
            Instant now) {
        requireOne(mapper.insertToolTrace(bytes(traceId), bytes(runId), toolName, riskLevel,
                schemaVersion, requestDigest, local(now)), "insert tool trace");
    }

    @Override
    public boolean completeToolTrace(
            UUID traceId,
            byte[] resultDigest,
            String resultCode,
            long latencyMs,
            Instant completedAt) {
        return mapper.completeToolTrace(bytes(traceId), resultDigest, resultCode,
                latencyMs, local(completedAt)) == 1;
    }

    @Override
    public int completeOpenToolTraces(UUID runId, byte[] resultDigest, String resultCode, Instant completedAt) {
        return mapper.completeOpenToolTraces(bytes(runId), resultDigest, resultCode, local(completedAt));
    }

    private static AiConversationPo toPo(AiConversation value) {
        return new AiConversationPo(bytes(value.id()), bytes(value.userId()), value.title(), value.status(),
                value.version(), value.nextMessageSequence(), local(value.lastMessageAt()), local(value.createdAt()),
                local(value.updatedAt()), local(value.deletedAt()));
    }

    private static AiConversation toDomain(AiConversationPo value) {
        return new AiConversation(uuid(value.id()), uuid(value.userId()), value.title(), value.status(),
                value.version(), value.nextMessageSequence(), instant(value.lastMessageAt()),
                instant(value.createdAt()), instant(value.updatedAt()), instant(value.deletedAt()));
    }

    private static AiMessagePo toPo(AiMessage value) {
        return new AiMessagePo(bytes(value.id()), bytes(value.conversationId()), bytes(value.runId()),
                value.role().name(), value.contentText(), value.cardType(), value.cardVersion(),
                value.cardPayload(), value.sequenceNo(), local(value.createdAt()));
    }

    private static AiMessage toDomain(AiMessagePo value) {
        return new AiMessage(uuid(value.id()), uuid(value.conversationId()), uuid(value.runId()),
                AiMessage.Role.valueOf(value.role()), value.contentText(), value.cardType(), value.cardVersion(),
                value.cardPayload(), value.sequenceNo(), instant(value.createdAt()));
    }

    private static AgentRunPo toPo(AgentRun value) {
        return new AgentRunPo(bytes(value.id()), bytes(value.conversationId()), bytes(value.userId()),
                bytes(value.clientMessageId()), value.idempotencyKeyHash(), value.requestHash(),
                value.contextVersion(), value.status().name(), value.intentType(), value.businessRefType(),
                value.businessRefId(), value.modelProvider(), value.modelName(), value.promptVersion(),
                value.toolCatalogVersion(), value.nextEventSequence(), value.cancelRequested(), value.version(),
                local(value.createdAt()), local(value.startedAt()), local(value.completedAt()), local(value.updatedAt()));
    }

    private static AgentRun toDomain(AgentRunPo value) {
        return new AgentRun(uuid(value.id()), uuid(value.conversationId()), uuid(value.userId()),
                uuid(value.clientMessageId()), value.idempotencyKeyHash(), value.requestHash(),
                value.contextVersion(), AgentRunStatus.valueOf(value.status()), value.intentType(),
                value.businessRefType(), value.businessRefId(), value.modelProvider(), value.modelName(),
                value.promptVersion(), value.toolCatalogVersion(), value.nextEventSequence(),
                value.cancelRequested(), value.version(), instant(value.createdAt()), instant(value.startedAt()),
                instant(value.completedAt()), instant(value.updatedAt()));
    }

    private static AgentRunEventPo toPo(AgentRunEvent value) {
        return new AgentRunEventPo(bytes(value.id()), bytes(value.runId()), value.sequenceNo(), value.eventType(),
                value.payloadVersion(), value.payload(), local(value.occurredAt()), local(value.expiresAt()));
    }

    private static AgentRunEvent toDomain(AgentRunEventPo value) {
        return new AgentRunEvent(uuid(value.id()), uuid(value.runId()), value.sequenceNo(), value.eventType(),
                value.payloadVersion(), value.payload(), instant(value.occurredAt()), instant(value.expiresAt()));
    }

    private static byte[] bytes(UUID value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) {
        if (value == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void requireOne(int changed, String operation) {
        if (changed != 1) {
            throw new IllegalStateException(operation + " expected one affected row, got " + changed);
        }
    }
}
