package com.minipay.agent.application.service;

import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.domain.model.ai.AiMessage;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemoryConversationService {
    private static final String TASK_TYPE = "memory-proposal";
    private static final Set<MemoryType> CHAT_TYPES = Set.of(
            MemoryType.FOOD_PREFERENCE,
            MemoryType.ALLERGEN_AVOIDANCE,
            MemoryType.MEAL_BUDGET);
    private static final List<String> TEMPORARY_MARKERS = List.of(
            "今天", "今晚", "这次", "本次", "暂时", "当前", "等会", "这一顿");
    private static final List<String> REUSABLE_MEMORY_MARKERS = List.of(
            "记住", "长期记忆", "以后", "今后", "每次", "一直", "通常", "习惯",
            "我喜欢", "喜欢吃", "我爱吃", "我不吃", "不喜欢吃", "不能吃", "不可以吃",
            "不能喝", "不可以喝", "忌口", "过敏", "不耐受", "禁忌", "预算", "每餐", "每顿");

    private final MemoryApplicationService memory;
    private final MemoryContentPolicy contentPolicy;
    private final ModelGateway model;
    private final AgentTaskStateStore taskStates;
    private final AgentRunApplicationService runs;
    private final AiAgentRepository repository;

    public MemoryConversationService(
            MemoryApplicationService memory,
            MemoryContentPolicy contentPolicy,
            ModelGateway model,
            AgentTaskStateStore taskStates,
            AgentRunApplicationService runs,
            AiAgentRepository repository) {
        this.memory = memory;
        this.contentPolicy = contentPolicy;
        this.model = model;
        this.taskStates = taskStates;
        this.runs = runs;
        this.repository = repository;
    }

    public ProposalResult propose(
            UUID userId,
            UUID runId,
            String userMessage,
            List<ModelGateway.ContextMessage> history) {
        // Most messages are ordinary conversation or business tasks. Avoid a second model call
        // unless the current utterance itself contains a durable-preference signal.
        if (!mayContainReusableMemory(userMessage)) return ProposalResult.NOT_APPLICABLE;
        MemorySetting setting = memory.setting(userId);
        if (!setting.enabled() || CHAT_TYPES.stream().noneMatch(setting::permits)) {
            return ProposalResult.NOT_SAVED;
        }

        ModelGateway.MemoryCandidate candidate;
        try {
            candidate = model.classifyMemory(
                            new ModelGateway.MemoryClassificationRequest(history, userMessage, "v1"))
                    .orElse(null);
        } catch (RuntimeException exception) {
            return ProposalResult.NOT_SAVED;
        }
        if (candidate == null || !candidate.candidate() || !candidate.longTerm()) {
            return ProposalResult.NOT_SAVED;
        }

        MemoryType type;
        try {
            type = MemoryType.valueOf(candidate.type());
        } catch (RuntimeException exception) {
            return ProposalResult.NOT_SAVED;
        }
        if (!CHAT_TYPES.contains(type) || !setting.permits(type)) return ProposalResult.NOT_SAVED;
        if (candidate.evidence() == null || candidate.evidence().isBlank()
                || !userMessage.contains(candidate.evidence().strip())) return ProposalResult.NOT_SAVED;
        if (TEMPORARY_MARKERS.stream().anyMatch(userMessage::contains)) return ProposalResult.NOT_SAVED;

        String displayValue;
        try {
            displayValue = contentPolicy.validateAndNormalize(candidate.displayValue());
        } catch (AgentApplicationException exception) {
            return ProposalResult.NOT_SAVED;
        }
        AiMessage source = sourceMessage(userId, runId);
        UUID candidateId = UuidV7.generate();
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("candidateId", candidateId.toString());
        slots.put("memoryType", type.name());
        slots.put("displayValue", displayValue);
        slots.put("consentMessageId", source.id().toString());
        taskStates.save(runId, TASK_TYPE, slots, Instant.now());
        runs.waitForConfirmation(userId, runId, "memory.confirmation", "memory.confirmation",
                Map.of(
                        "candidateId", candidateId.toString(),
                        "memoryType", type.name(),
                        "displayValue", displayValue,
                        "confirmationRequired", true),
                "我识别到一条可能长期有用的信息，请确认是否保存。");
        return ProposalResult.PROPOSED;
    }

    public boolean recall(UUID userId, UUID runId, String userMessage) {
        MemoryType type = recallType(userMessage);
        if (type == null) return false;
        MemorySetting setting = memory.setting(userId);
        String label = memoryLabel(type);
        if (!setting.enabled() || !setting.permits(type)) {
            runs.completeTextRun(userId, runId,
                    "你尚未开启" + label + "长期记忆，可以在个人中心的“记忆”中开启。");
            return true;
        }
        List<String> values = memory.list(userId, type, 20).stream()
                .map(item -> item.displayValue())
                .toList();
        if (values.isEmpty()) {
            runs.completeTextRun(userId, runId,
                    "你目前没有已保存的" + label + "信息。只有确认保存后的内容才会出现在这里。");
        } else {
            runs.completeTextRun(userId, runId,
                    "你已保存的" + label + "：" + String.join("；", values) + "。");
        }
        return true;
    }

    private boolean mayContainReusableMemory(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        return REUSABLE_MEMORY_MARKERS.stream().anyMatch(userMessage::contains);
    }

    private MemoryType recallType(String message) {
        if (message == null || message.isBlank()) return null;
        boolean possessiveLookup = message.contains("我的")
                && List.of("是", "为", "包括", "包含", "设置成", "改成")
                        .stream().noneMatch(message::contains);
        boolean question = possessiveLookup
                || List.of("什么", "哪些", "多少", "查询", "查看", "告诉我", "记得吗")
                        .stream().anyMatch(message::contains);
        if (!question) return null;
        if (List.of("不能吃", "不能喝", "忌口", "过敏", "不耐受")
                .stream().anyMatch(message::contains)) {
            return MemoryType.ALLERGEN_AVOIDANCE;
        }
        if (List.of("喜欢吃", "饮食偏好", "餐饮偏好")
                .stream().anyMatch(message::contains)) {
            return MemoryType.FOOD_PREFERENCE;
        }
        if (List.of("用餐预算", "餐饮预算", "吃饭预算")
                .stream().anyMatch(message::contains)) {
            return MemoryType.MEAL_BUDGET;
        }
        return null;
    }

    private String memoryLabel(MemoryType type) {
        return switch (type) {
            case ALLERGEN_AVOIDANCE -> "忌口与过敏原";
            case FOOD_PREFERENCE -> "餐饮偏好";
            case MEAL_BUDGET -> "用餐预算";
            default -> "长期记忆";
        };
    }

    public enum ProposalResult {
        NOT_APPLICABLE,
        PROPOSED,
        NOT_SAVED
    }

    public void confirm(UUID userId, UUID runId, UUID candidateId) {
        var run = runs.getRun(userId, runId);
        Map<String, Object> slots = proposal(userId, runId, candidateId);
        MemoryType type = MemoryType.valueOf(String.valueOf(slots.get("memoryType")));
        String value = String.valueOf(slots.get("displayValue"));
        UUID consentMessageId = UUID.fromString(String.valueOf(slots.get("consentMessageId")));
        var saved = memory.create(userId, type, value, null, null, consentMessageId,
                "memory-confirm:" + candidateId);
        taskStates.clear(userId, run.conversationId(), TASK_TYPE);
        runs.completeStructuredRun(userId, runId, "memory.saved", "memory.saved",
                Map.of(
                        "memoryId", saved.id().toString(),
                        "memoryType", saved.type().name(),
                        "displayValue", saved.displayValue()),
                "已保存到长期记忆，你可以随时在个人中心查看或修改。");
    }

    public void dismiss(UUID userId, UUID runId, UUID candidateId) {
        var run = runs.getRun(userId, runId);
        proposal(userId, runId, candidateId);
        taskStates.clear(userId, run.conversationId(), TASK_TYPE);
        runs.completeTextRun(userId, runId, "好的，这条信息只在本次会话中使用。");
    }

    private Map<String, Object> proposal(UUID userId, UUID runId, UUID candidateId) {
        Map<String, Object> slots = taskStates.findForRun(userId, runId, TASK_TYPE)
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_MEMORY_PROPOSAL_EXPIRED", "长期记忆候选已失效，请重新告诉我需要记住的内容"))
                .slots();
        if (!candidateId.toString().equals(String.valueOf(slots.get("candidateId")))) {
            throw new AgentApplicationException(
                    "AGENT_MEMORY_PROPOSAL_INVALID", "长期记忆候选不匹配");
        }
        return slots;
    }

    private AiMessage sourceMessage(UUID userId, UUID runId) {
        var run = runs.getRun(userId, runId);
        return repository.listMessages(userId, run.conversationId(), null, 24).stream()
                .filter(message -> runId.equals(message.runId()) && message.role() == AiMessage.Role.USER)
                .findFirst()
                .orElseThrow(() -> new AgentApplicationException(
                        "AGENT_MEMORY_CONSENT_INVALID", "未找到长期记忆对应的用户消息"));
    }
}
