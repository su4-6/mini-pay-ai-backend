package com.minipay.agent.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.application.port.RunEventPublisher;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunEvent;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import com.minipay.agent.domain.model.ai.AiConversation;
import com.minipay.agent.domain.model.ai.AiMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AgentRunApplicationService {
    private static final int MAX_EVENT_PAGE_SIZE = 500;

    private final AiAgentRepository repository;
    private final SensitiveInputSanitizer sanitizer;
    private final RunEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration eventRetention;
    private final int maxConcurrentRunsPerUser;
    private final Duration staleRunTimeout;

    @Autowired
    public AgentRunApplicationService(
            AiAgentRepository repository,
            SensitiveInputSanitizer sanitizer,
            RunEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            @Value("${minipay.agent.event-retention:24h}") Duration eventRetention,
            @Value("${minipay.agent.runs.max-concurrent-per-user:3}") int maxConcurrentRunsPerUser,
            @Value("${minipay.agent.runs.stale-timeout:3m}") Duration staleRunTimeout) {
        this(repository, sanitizer, eventPublisher, objectMapper, Clock.systemUTC(), eventRetention,
                maxConcurrentRunsPerUser, staleRunTimeout);
    }

    AgentRunApplicationService(
            AiAgentRepository repository,
            SensitiveInputSanitizer sanitizer,
            RunEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            Clock clock,
            Duration eventRetention,
            int maxConcurrentRunsPerUser,
            Duration staleRunTimeout) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.eventRetention = eventRetention;
        if (maxConcurrentRunsPerUser < 1 || maxConcurrentRunsPerUser > 20) {
            throw new IllegalArgumentException("maxConcurrentRunsPerUser must be between 1 and 20");
        }
        this.maxConcurrentRunsPerUser = maxConcurrentRunsPerUser;
        this.staleRunTimeout = staleRunTimeout;
    }

    @Transactional
    public CreateRunResult createRun(
            UUID userId,
            UUID conversationId,
            UUID clientMessageId,
            String idempotencyKey,
            String message,
            long contextVersion) {
        validateIdempotencyKey(idempotencyKey);
        SensitiveInputSanitizer.SanitizedInput sanitized = sanitizer.sanitize(message);
        byte[] idempotencyHash = digest(idempotencyKey);
        byte[] requestHash = digest(conversationId + "\n" + clientMessageId + "\n"
                + sanitized.text() + "\n" + contextVersion);

        Instant now = clock.instant();
        repository.lockUserRunGate(userId, now);

        AgentRun existing = repository.findRunByIdempotencyHash(userId, conversationId, idempotencyHash)
                .orElse(null);
        if (existing != null) {
            if (!Arrays.equals(existing.requestHash(), requestHash)) {
                throw new AgentApplicationException(
                        "AGENT_IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求");
            }
            return new CreateRunResult(
                    existing, true, sanitized.text(), sanitized.exactMobile().orElse(null));
        }

        AiConversation conversation = repository.lockConversation(userId, conversationId)
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_CONVERSATION_NOT_FOUND", "AI 会话不存在或不可访问"));
        ensureAdmissionAvailable(userId, conversationId, null);
        if (conversation.version() != contextVersion) {
            throw new AgentApplicationException(
                    "AGENT_CONVERSATION_VERSION_CONFLICT", "AI 会话已更新，请刷新后重试");
        }

        AgentRun run = new AgentRun(
                UuidV7.generate(), conversationId, userId, clientMessageId,
                idempotencyHash, requestHash, contextVersion, AgentRunStatus.RECEIVED,
                null, null, null, null, null, "v1", "v1", 1,
                false, 0, now, null, null, now);
        repository.insertRun(run);

        long messageSequence = repository.reserveMessageSequence(userId, conversationId, now);
        repository.insertMessage(new AiMessage(
                UuidV7.generate(), conversationId, run.id(), AiMessage.Role.USER,
                sanitized.text(), null, null, null, messageSequence, now));
        repository.assignInitialTitle(userId, conversationId, initialTitle(sanitized.text()), now);

        appendEventInternal(run.id(), "run.accepted", Map.of(
                "status", run.status().name(),
                "conversationId", conversationId.toString()), now);
        return new CreateRunResult(
                run, false, sanitized.text(), sanitized.exactMobile().orElse(null));
    }

    @Transactional(readOnly = true)
    public AgentRun getRun(UUID userId, UUID runId) {
        return repository.findRun(userId, runId)
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_RUN_NOT_FOUND", "AI 任务不存在或不可访问"));
    }

    @Transactional(readOnly = true)
    public List<AgentRun> listActiveRuns(UUID userId) {
        return repository.listActiveRuns(userId, maxConcurrentRunsPerUser);
    }

    @Transactional
    public AgentRun resumeExecution(UUID userId, UUID runId) {
        AgentRun current = getRun(userId, runId);
        repository.lockUserRunGate(userId, clock.instant());
        repository.lockConversation(userId, current.conversationId())
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_CONVERSATION_NOT_FOUND", "AI 会话不存在或不可访问"));
        ensureAdmissionAvailable(userId, current.conversationId(), runId);
        return transition(userId, runId, AgentRunStatus.UNDERSTANDING);
    }

    @Transactional
    public AgentRun transition(UUID userId, UUID runId, AgentRunStatus target) {
        AgentRun current = getRun(userId, runId);
        if (current.status() == target) {
            return current;
        }
        if (!current.status().canTransitionTo(target)) {
            throw new AgentApplicationException(
                    "AGENT_RUN_STATE_CONFLICT", "AI 任务状态已变化，请刷新后重试");
        }
        Instant now = clock.instant();
        Instant startedAt = target == AgentRunStatus.UNDERSTANDING ? now : current.startedAt();
        Instant completedAt = target.isTerminal() ? now : null;
        if (!repository.transitionRun(userId, runId, current.status(), target, current.version(),
                startedAt, completedAt, now)) {
            throw new AgentApplicationException(
                    "AGENT_RUN_STATE_CONFLICT", "AI 任务状态已变化，请刷新后重试");
        }
        appendEventInternal(runId, "run.status", Map.of("status", target.name()), now);
        return getRun(userId, runId);
    }

    @Transactional
    public AgentRun cancel(UUID userId, UUID runId) {
        AgentRun current = getRun(userId, runId);
        if (current.status().isTerminal()) {
            return current;
        }
        repository.requestCancellation(userId, runId, clock.instant());
        return transition(userId, runId, AgentRunStatus.CANCELLED);
    }

    @Transactional
    public AgentRunEvent appendEvent(UUID userId, UUID runId, String eventType, Object payload) {
        getRun(userId, runId);
        return appendEventInternal(runId, eventType, payload, clock.instant());
    }

    @Transactional
    public UUID startToolTrace(UUID userId, UUID runId, String toolName, String riskLevel) {
        getRun(userId, runId);
        UUID traceId = UuidV7.generate();
        repository.insertToolTrace(traceId, runId, toolName, riskLevel, 1,
                digest(runId + "\n" + toolName), clock.instant());
        appendEventInternal(runId, "tool.status", Map.of(
                "traceId", traceId.toString(),
                "tool", toolName,
                "status", "RUNNING"), clock.instant());
        return traceId;
    }

    @Transactional
    public void completeToolTrace(
            UUID userId, UUID runId, UUID traceId, String toolName, String resultCode) {
        getRun(userId, runId);
        Instant now = clock.instant();
        if (!repository.completeToolTrace(traceId, digest(resultCode), resultCode, 0, now)) {
            throw new AgentApplicationException(
                    "AGENT_TOOL_TRACE_CONFLICT", "工具执行轨迹状态异常");
        }
        appendEventInternal(runId, "tool.status", Map.of(
                "traceId", traceId.toString(),
                "tool", toolName,
                "status", "SUCCEEDED"), now);
    }

    @Transactional
    public void completeTextRun(UUID userId, UUID runId, String assistantText) {
        AgentRun current = getRun(userId, runId);
        if (current.status().isTerminal()) {
            return;
        }
        if (assistantText == null || assistantText.isBlank()) {
            throw new AgentApplicationException(
                    "AGENT_MODEL_EMPTY_RESPONSE", "模型未返回有效内容，请稍后重试");
        }
        String safeText = assistantText.length() > 4096
                ? assistantText.substring(0, 4096)
                : assistantText;
        Instant now = clock.instant();
        long messageSequence = repository.reserveMessageSequence(userId, current.conversationId(), now);
        UUID messageId = UuidV7.generate();
        repository.insertMessage(new AiMessage(
                messageId, current.conversationId(), runId, AiMessage.Role.ASSISTANT,
                safeText, null, null, null, messageSequence, now));
        appendEventInternal(runId, "message.completed", Map.of(
                "messageId", messageId.toString(),
                "sequenceNo", messageSequence), now);
        transition(userId, runId, AgentRunStatus.COMPLETED);
        appendEventInternal(runId, "task.result", Map.of(
                "status", AgentRunStatus.COMPLETED.name()), now);
        appendEventInternal(runId, "stream.completed", Map.of(), now);
    }

    @Transactional
    public void completeStructuredRun(
            UUID userId,
            UUID runId,
            String eventType,
            String cardType,
            Object cardPayload,
            String assistantText) {
        appendStructuredMessage(userId, runId, eventType, cardType, cardPayload, assistantText);
        transition(userId, runId, AgentRunStatus.COMPLETED);
        Instant now = clock.instant();
        appendEventInternal(runId, "task.result", Map.of(
                "status", AgentRunStatus.COMPLETED.name()), now);
        appendEventInternal(runId, "stream.completed", Map.of(), now);
    }

    @Transactional
    public void completeBusinessReplyRun(
            UUID userId, UUID runId, String resultCode, String assistantText) {
        repository.completeOpenToolTraces(
                runId, digest(resultCode), resultCode, clock.instant());
        completeTextRun(userId, runId, assistantText);
    }

    @Transactional
    public void completeSilentRun(UUID userId, UUID runId) {
        AgentRun current = getRun(userId, runId);
        if (current.status().isTerminal()) {
            return;
        }
        transition(userId, runId, AgentRunStatus.COMPLETED);
        Instant now = clock.instant();
        appendEventInternal(runId, "task.result", Map.of(
                "status", AgentRunStatus.COMPLETED.name()), now);
        appendEventInternal(runId, "stream.completed", Map.of(), now);
    }

    @Transactional
    public void waitForConfirmation(
            UUID userId,
            UUID runId,
            String eventType,
            String cardType,
            Object cardPayload,
            String assistantText) {
        appendStructuredMessage(userId, runId, eventType, cardType, cardPayload, assistantText);
        transition(userId, runId, AgentRunStatus.WAITING_CONFIRMATION);
        appendEventInternal(runId, "stream.completed", Map.of(), clock.instant());
    }

    @Transactional
    public void waitForInputCard(
            UUID userId,
            UUID runId,
            String eventType,
            String cardType,
            Object cardPayload,
            String assistantText) {
        appendStructuredMessage(userId, runId, eventType, cardType, cardPayload, assistantText);
        transition(userId, runId, AgentRunStatus.WAITING_INPUT);
        appendEventInternal(runId, "stream.completed", Map.of(), clock.instant());
    }

    @Transactional
    public void requestInput(
            UUID userId,
            UUID runId,
            String taskType,
            Object missingSlots,
            String assistantText) {
        appendStructuredMessage(
                userId,
                runId,
                "choice.card",
                "agent.missing-slots",
                Map.of("taskType", taskType, "missingSlots", missingSlots),
                assistantText);
        transition(userId, runId, AgentRunStatus.WAITING_INPUT);
        appendEventInternal(runId, "stream.completed", Map.of(), clock.instant());
    }

    @Transactional
    public void failRun(UUID userId, UUID runId, String code, String safeMessage) {
        AgentRun current = getRun(userId, runId);
        if (current.status().isTerminal()) {
            return;
        }
        Instant now = clock.instant();
        repository.completeOpenToolTraces(runId, digest(code), code, now);
        appendEventInternal(runId, "task.error", Map.of(
                "code", code,
                "message", safeMessage,
                "retryable", code.startsWith("AGENT_MODEL_") || code.equals("AGENT_INTERNAL_ERROR")), now);
        transition(userId, runId, AgentRunStatus.FAILED);
        appendEventInternal(runId, "stream.completed", Map.of(), now);
    }

    @Scheduled(fixedDelayString = "${minipay.agent.runs.stale-scan-delay:30s}")
    @Transactional
    void expireStaleRuns() {
        Instant cutoff = clock.instant().minus(staleRunTimeout);
        repository.listStaleActiveRuns(cutoff, 100).forEach(run ->
                failRun(run.userId(), run.id(), "AGENT_RUN_STALE",
                        "AI 任务处理超时，请重新发送"));
    }

    @Transactional(readOnly = true)
    public List<AgentRunEvent> listEvents(UUID userId, UUID runId, long afterSequence, int requestedLimit) {
        getRun(userId, runId);
        if (afterSequence < 0) {
            throw new AgentApplicationException("AGENT_EVENT_CURSOR_INVALID", "事件游标无效");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_EVENT_PAGE_SIZE));
        return repository.listRunEvents(userId, runId, afterSequence, limit);
    }

    private AgentRunEvent appendEventInternal(UUID runId, String eventType, Object payload, Instant now) {
        long sequence = repository.reserveEventSequence(runId, now);
        AgentRunEvent event = new AgentRunEvent(
                UuidV7.generate(), runId, sequence, eventType, 1,
                json(payload), now, now.plus(eventRetention));
        repository.insertRunEvent(event);
        publishAfterCommit(event);
        return event;
    }

    private void ensureAdmissionAvailable(UUID userId, UUID conversationId, UUID excludedRunId) {
        if (repository.hasActiveRun(userId, conversationId, excludedRunId)) {
            throw new AgentApplicationException(
                    "AGENT_CONVERSATION_RUN_ACTIVE", "当前会话已有任务正在生成");
        }
        if (repository.countActiveRuns(userId, excludedRunId) >= maxConcurrentRunsPerUser) {
            throw new AgentApplicationException(
                    "AGENT_CONCURRENCY_LIMIT_REACHED", "最多同时进行3个AI对话");
        }
    }

    private void appendStructuredMessage(
            UUID userId,
            UUID runId,
            String eventType,
            String cardType,
            Object cardPayload,
            String assistantText) {
        AgentRun current = getRun(userId, runId);
        if (current.status().isTerminal()) return;
        if (assistantText == null || assistantText.isBlank()) {
            throw new AgentApplicationException(
                    "AGENT_ASSISTANT_TEXT_REQUIRED", "结构化消息缺少安全说明");
        }
        Instant now = clock.instant();
        long messageSequence = repository.reserveMessageSequence(userId, current.conversationId(), now);
        UUID messageId = UuidV7.generate();
        repository.insertMessage(new AiMessage(
                messageId, current.conversationId(), runId, AiMessage.Role.ASSISTANT,
                assistantText, cardType, 1, json(cardPayload), messageSequence, now));
        appendEventInternal(runId, eventType, cardPayload, now);
        appendEventInternal(runId, "message.completed", Map.of(
                "messageId", messageId.toString(),
                "sequenceNo", messageSequence,
                "cardType", cardType), now);
    }

    private void publishAfterCommit(AgentRunEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(event);
                }
            });
        } else {
            eventPublisher.publish(event);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize safe agent event payload", exception);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new AgentApplicationException(
                    "AGENT_IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 长度必须为 8 到 128 个字符");
        }
    }

    static String initialTitle(String sanitizedText) {
        String normalized = sanitizedText == null ? "" : sanitizedText
                .replace("[MOBILE_EXACT]", "手机号")
                .replaceAll("\\s+", " ").strip();
        String[] sentence = normalized.split("[。！？!?\\r\\n]", 2);
        String title = sentence.length == 0 ? "" : sentence[0].strip();
        if (title.isEmpty()) return "未命名对话";
        int[] codePoints = title.codePoints().toArray();
        return codePoints.length <= 20 ? title : new String(codePoints, 0, 20) + "…";
    }

    public record CreateRunResult(
            AgentRun run,
            boolean replayed,
            String sanitizedMessage,
            String transientExactMobile) {
    }
}
