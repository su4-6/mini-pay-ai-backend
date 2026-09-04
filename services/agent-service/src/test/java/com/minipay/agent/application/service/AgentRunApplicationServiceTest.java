package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.application.port.RunEventPublisher;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunEvent;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import com.minipay.agent.domain.model.ai.AiConversation;
import com.minipay.agent.domain.model.ai.AiMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunApplicationServiceTest {
    private static final UUID USER_ID = UUID.fromString("0198f000-0000-7000-8000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("0198f000-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");

    private InMemoryRepository repository;
    private CapturingPublisher publisher;
    private AgentRunApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        publisher = new CapturingPublisher();
        repository.insertConversation(new AiConversation(
                CONVERSATION_ID, USER_ID, "新对话", "ACTIVE", 0, 1,
                NOW, NOW, NOW, null));
        service = new AgentRunApplicationService(
                repository, new SensitiveInputSanitizer(), publisher, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24), 3, Duration.ofMinutes(3));
    }

    @Test
    void persistsOnlyRedactedMessageAndCreatesRecoverableEvent() {
        UUID clientMessageId = UUID.fromString("0198f000-0000-7000-8000-000000000003");

        AgentRunApplicationService.CreateRunResult result = service.createRun(
                USER_ID, CONVERSATION_ID, clientMessageId, "request-key-0001",
                "给 13800138000 转 50 元", 0);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transientExactMobile()).isEqualTo("13800138000");
        assertThat(repository.messages).singleElement().satisfies(message -> {
            assertThat(message.contentText()).isEqualTo("给 [MOBILE_EXACT] 转 50 元");
            assertThat(message.contentText()).doesNotContain("13800138000");
        });
        assertThat(repository.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("run.accepted");
            assertThat(event.payload()).doesNotContain("13800138000");
            assertThat(event.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        });
        assertThat(publisher.events).containsExactlyElementsOf(repository.events);
    }

    @Test
    void rejectsSecondActiveRunInSameConversation() {
        service.createRun(USER_ID, CONVERSATION_ID, UUID.randomUUID(), "same-conversation-1", "你好", 0);

        assertThatThrownBy(() -> service.createRun(
                USER_ID, CONVERSATION_ID, UUID.randomUUID(), "same-conversation-2", "再问一次", 1))
                .isInstanceOf(AgentApplicationException.class)
                .extracting(error -> ((AgentApplicationException) error).code())
                .isEqualTo("AGENT_CONVERSATION_RUN_ACTIVE");
    }

    @Test
    void limitsActiveRunsAcrossDifferentConversations() {
        for (int index = 0; index < 4; index++) {
            UUID conversationId = UUID.randomUUID();
            repository.insertConversation(new AiConversation(
                    conversationId, USER_ID, "对话" + index, "ACTIVE", 0, 1,
                    NOW, NOW, NOW, null));
            if (index < 3) {
                service.createRun(USER_ID, conversationId, UUID.randomUUID(),
                        "parallel-run-" + index, "问题" + index, 0);
            } else {
                assertThatThrownBy(() -> service.createRun(
                        USER_ID, conversationId, UUID.randomUUID(), "parallel-run-3", "第四个问题", 0))
                        .isInstanceOf(AgentApplicationException.class)
                        .extracting(error -> ((AgentApplicationException) error).code())
                        .isEqualTo("AGENT_CONCURRENCY_LIMIT_REACHED");
            }
        }
    }

    @Test
    void reusesSameRunForSameIdempotentRequestAndRejectsDifferentPayload() {
        UUID clientMessageId = UUID.fromString("0198f000-0000-7000-8000-000000000004");
        AgentRunApplicationService.CreateRunResult first = service.createRun(
                USER_ID, CONVERSATION_ID, clientMessageId, "request-key-0002", "余额多少", 0);
        AgentRunApplicationService.CreateRunResult replay = service.createRun(
                USER_ID, CONVERSATION_ID, clientMessageId, "request-key-0002", "余额多少", 0);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(repository.runs).hasSize(1);
        assertThat(repository.messages).hasSize(1);

        assertThatThrownBy(() -> service.createRun(
                USER_ID, CONVERSATION_ID,
                UUID.fromString("0198f000-0000-7000-8000-000000000005"),
                "request-key-0002", "最近账单", 1))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AGENT_IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsCredentialWithoutWritingAnyRunOrMessage() {
        assertThatThrownBy(() -> service.createRun(
                USER_ID, CONVERSATION_ID,
                UUID.fromString("0198f000-0000-7000-8000-000000000006"),
                "request-key-0003", "验证码是 123456", 0))
                .isInstanceOfSatisfying(AgentApplicationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("AGENT_SENSITIVE_CREDENTIAL_REJECTED"));

        assertThat(repository.runs).isEmpty();
        assertThat(repository.messages).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    @Test
    void persistsAssistantPromptWhenRunWaitsForMissingInput() {
        AgentRunApplicationService.CreateRunResult created = service.createRun(
                USER_ID, CONVERSATION_ID,
                UUID.fromString("0198f000-0000-7000-8000-000000000007"),
                "request-key-0004", "我要转账", 0);
        service.transition(USER_ID, created.run().id(), AgentRunStatus.UNDERSTANDING);

        service.requestInput(
                USER_ID,
                created.run().id(),
                "transfer",
                Map.of("recipient", "请输入收款人", "amount", "请输入金额"),
                "请告诉我收款人和转账金额。");

        assertThat(repository.messages).hasSize(2);
        assertThat(repository.messages.get(1)).satisfies(message -> {
            assertThat(message.role()).isEqualTo(AiMessage.Role.ASSISTANT);
            assertThat(message.contentText()).isEqualTo("请告诉我收款人和转账金额。");
            assertThat(message.cardType()).isEqualTo("agent.missing-slots");
            assertThat(message.cardPayload()).contains("recipient", "amount");
        });
        assertThat(repository.events).extracting(AgentRunEvent::eventType)
                .contains("choice.card", "message.completed", "stream.completed");
        assertThat(service.getRun(USER_ID, created.run().id()).status())
                .isEqualTo(AgentRunStatus.WAITING_INPUT);
    }

    private static final class CapturingPublisher implements RunEventPublisher {
        private final List<AgentRunEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentRunEvent event) {
            events.add(event);
        }
    }

    private static final class InMemoryRepository implements AiAgentRepository {
        private final Map<UUID, AiConversation> conversations = new LinkedHashMap<>();
        private final Map<UUID, AgentRun> runs = new LinkedHashMap<>();
        private final List<AiMessage> messages = new ArrayList<>();
        private final List<AgentRunEvent> events = new ArrayList<>();
        private final Set<UUID> runningToolTraces = new HashSet<>();

        @Override
        public void insertConversation(AiConversation conversation) {
            conversations.put(conversation.id(), conversation);
        }

        @Override
        public Optional<AiConversation> findConversation(UUID userId, UUID conversationId) {
            return Optional.ofNullable(conversations.get(conversationId))
                    .filter(value -> value.userId().equals(userId) && value.deletedAt() == null);
        }

        @Override
        public List<AiConversation> listConversations(UUID userId, Instant beforeTime, UUID beforeId, int limit) {
            return conversations.values().stream().filter(value -> value.userId().equals(userId)).limit(limit).toList();
        }

        @Override
        public boolean renameConversation(UUID userId, UUID conversationId, String title,
                                          long expectedVersion, Instant now) {
            return false;
        }

        @Override
        public boolean assignInitialTitle(UUID userId, UUID conversationId, String title, Instant now) {
            return false;
        }

        @Override
        public boolean softDeleteConversation(UUID userId, UUID conversationId, Instant now) {
            return false;
        }

        @Override
        public long reserveMessageSequence(UUID userId, UUID conversationId, Instant now) {
            AiConversation value = findConversation(userId, conversationId).orElseThrow();
            long sequence = value.nextMessageSequence();
            conversations.put(conversationId, new AiConversation(
                    value.id(), value.userId(), value.title(), value.status(), value.version() + 1,
                    sequence + 1, now, value.createdAt(), now, null));
            return sequence;
        }

        @Override
        public void insertMessage(AiMessage message) {
            messages.add(message);
        }

        @Override
        public List<AiMessage> listMessages(UUID userId, UUID conversationId, Long beforeSequence, int limit) {
            return messages.stream().filter(value -> value.conversationId().equals(conversationId)).limit(limit).toList();
        }

        @Override
        public void insertRun(AgentRun run) {
            runs.put(run.id(), run);
        }

        @Override
        public Optional<AgentRun> findRun(UUID userId, UUID runId) {
            return Optional.ofNullable(runs.get(runId)).filter(value -> value.userId().equals(userId));
        }

        @Override
        public Optional<AgentRun> findRunByIdempotencyHash(
                UUID userId, UUID conversationId, byte[] idempotencyHash) {
            return runs.values().stream()
                    .filter(value -> value.userId().equals(userId)
                            && value.conversationId().equals(conversationId)
                            && Arrays.equals(value.idempotencyKeyHash(), idempotencyHash))
                    .findFirst();
        }

        @Override
        public void lockUserRunGate(UUID userId, Instant now) {
        }

        @Override
        public Optional<AiConversation> lockConversation(UUID userId, UUID conversationId) {
            return findConversation(userId, conversationId);
        }

        @Override
        public int countActiveRuns(UUID userId, UUID excludedRunId) {
            return (int) runs.values().stream()
                    .filter(value -> value.userId().equals(userId))
                    .filter(value -> excludedRunId == null || !value.id().equals(excludedRunId))
                    .filter(InMemoryRepository::active)
                    .count();
        }

        @Override
        public boolean hasActiveRun(UUID userId, UUID conversationId, UUID excludedRunId) {
            return runs.values().stream()
                    .filter(value -> value.userId().equals(userId)
                            && value.conversationId().equals(conversationId))
                    .filter(value -> excludedRunId == null || !value.id().equals(excludedRunId))
                    .anyMatch(InMemoryRepository::active);
        }

        @Override
        public List<AgentRun> listActiveRuns(UUID userId, int limit) {
            return runs.values().stream().filter(value -> value.userId().equals(userId))
                    .filter(InMemoryRepository::active).limit(limit).toList();
        }

        @Override
        public List<AgentRun> listStaleActiveRuns(Instant updatedBefore, int limit) {
            return runs.values().stream().filter(InMemoryRepository::active)
                    .filter(value -> value.updatedAt().isBefore(updatedBefore)).limit(limit).toList();
        }

        @Override
        public boolean transitionRun(UUID userId, UUID runId, AgentRunStatus source, AgentRunStatus target,
                                     long expectedVersion, Instant startedAt, Instant completedAt, Instant now) {
            AgentRun value = runs.get(runId);
            if (value == null || !value.userId().equals(userId) || value.status() != source
                    || value.version() != expectedVersion) {
                return false;
            }
            runs.put(runId, new AgentRun(
                    value.id(), value.conversationId(), value.userId(), value.clientMessageId(),
                    value.idempotencyKeyHash(), value.requestHash(), value.contextVersion(), target,
                    value.intentType(), value.businessRefType(), value.businessRefId(), value.modelProvider(),
                    value.modelName(), value.promptVersion(), value.toolCatalogVersion(), value.nextEventSequence(),
                    value.cancelRequested(), value.version() + 1, value.createdAt(),
                    value.startedAt() == null ? startedAt : value.startedAt(), completedAt, now));
            return true;
        }

        @Override
        public boolean requestCancellation(UUID userId, UUID runId, Instant now) {
            AgentRun value = runs.get(runId);
            if (value == null || !value.userId().equals(userId) || value.status().isTerminal()) {
                return false;
            }
            runs.put(runId, new AgentRun(
                    value.id(), value.conversationId(), value.userId(), value.clientMessageId(),
                    value.idempotencyKeyHash(), value.requestHash(), value.contextVersion(), value.status(),
                    value.intentType(), value.businessRefType(), value.businessRefId(), value.modelProvider(),
                    value.modelName(), value.promptVersion(), value.toolCatalogVersion(), value.nextEventSequence(),
                    true, value.version(), value.createdAt(), value.startedAt(), value.completedAt(), now));
            return true;
        }

        @Override
        public long reserveEventSequence(UUID runId, Instant now) {
            AgentRun value = runs.get(runId);
            long sequence = value.nextEventSequence();
            runs.put(runId, new AgentRun(
                    value.id(), value.conversationId(), value.userId(), value.clientMessageId(),
                    value.idempotencyKeyHash(), value.requestHash(), value.contextVersion(), value.status(),
                    value.intentType(), value.businessRefType(), value.businessRefId(), value.modelProvider(),
                    value.modelName(), value.promptVersion(), value.toolCatalogVersion(), sequence + 1,
                    value.cancelRequested(), value.version(), value.createdAt(), value.startedAt(),
                    value.completedAt(), now));
            return sequence;
        }

        @Override
        public void insertRunEvent(AgentRunEvent event) {
            events.add(event);
        }

        @Override
        public List<AgentRunEvent> listRunEvents(UUID userId, UUID runId, long afterSequence, int limit) {
            return events.stream()
                    .filter(value -> value.runId().equals(runId) && value.sequenceNo() > afterSequence)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void insertToolTrace(
                UUID traceId, UUID runId, String toolName, String riskLevel,
                int schemaVersion, byte[] requestDigest, Instant now) {
            runningToolTraces.add(traceId);
        }

        @Override
        public boolean completeToolTrace(
                UUID traceId, byte[] resultDigest, String resultCode,
                long latencyMs, Instant completedAt) {
            return runningToolTraces.remove(traceId);
        }

        @Override
        public int completeOpenToolTraces(UUID runId, byte[] resultDigest, String resultCode, Instant completedAt) {
            int count = runningToolTraces.size();
            runningToolTraces.clear();
            return count;
        }

        private static boolean active(AgentRun value) {
            return value.status() == AgentRunStatus.RECEIVED
                    || value.status() == AgentRunStatus.UNDERSTANDING
                    || value.status() == AgentRunStatus.EXECUTING_TOOL;
        }
    }
}
