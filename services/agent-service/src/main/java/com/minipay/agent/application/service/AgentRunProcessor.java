package com.minipay.agent.application.service;

import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.domain.model.ai.AiMessage;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AgentRunProcessor {
    private static final String SYSTEM_PROMPT = """
            你是 MiniPay Android 沙箱智能体。只回答一般性、非交易问题。
            不得虚构余额、账单、价格、库存、订单或支付状态；不得要求用户提供密码、验证码或令牌。
            涉及资金或外卖时，业务编排器会使用权威工具，不允许你自行给出交易结果。
            你具备受用户授权和确认控制的长期记忆能力。不得声称自己没有长期记忆；
            当系统没有展示记忆确认卡时，也不得声称内容已经保存。
            使用简洁中文回答。
            """;
    private static final String PAUSED_TRANSFER_SYSTEM_PROMPT = """

            当前有一笔未完成的转账处于暂停状态。优先回答用户当前的新话题，
            不得把当前内容解释为收款人或金额，也不得自行继续、取消或执行转账。
            """;
    private static final String PENDING_TRANSFER_REMINDER =
            "\n\n另外，刚才的转账还未完成，需要时可以说“继续转账”。";

    private final AgentRunApplicationService runs;
    private final AgentIntentClassifier intentClassifier;
    private final AgentBusinessOrchestrator businessOrchestrator;
    private final ModelGateway modelGateway;
    private final AiAgentRepository repository;
    private final MemoryContextService memoryContext;
    private final MemoryConversationService memoryConversation;
    private final ExecutorService executor;

    public AgentRunProcessor(
            AgentRunApplicationService runs,
            AgentIntentClassifier intentClassifier,
            AgentBusinessOrchestrator businessOrchestrator,
            ModelGateway modelGateway,
            AiAgentRepository repository,
            MemoryContextService memoryContext,
            MemoryConversationService memoryConversation,
            @Qualifier("agentRunExecutor") ExecutorService executor) {
        this.runs = runs;
        this.intentClassifier = intentClassifier;
        this.businessOrchestrator = businessOrchestrator;
        this.modelGateway = modelGateway;
        this.repository = repository;
        this.memoryContext = memoryContext;
        this.memoryConversation = memoryConversation;
        this.executor = executor;
    }

    public void start(
            UUID userId,
            UUID runId,
            String sanitizedMessage,
            String transientExactMobile,
            UUID locationContextId,
            String androidAccessToken) {
        executor.submit(() -> process(
                userId, runId, sanitizedMessage, transientExactMobile,
                locationContextId, androidAccessToken));
    }

    void process(
            UUID userId,
            UUID runId,
            String sanitizedMessage,
            String transientExactMobile,
            String androidAccessToken) {
        process(userId, runId, sanitizedMessage, transientExactMobile, null, androidAccessToken);
    }

    void process(
            UUID userId,
            UUID runId,
            String sanitizedMessage,
            String transientExactMobile,
            UUID locationContextId,
            String androidAccessToken) {
        try {
            runs.transition(userId, runId, AgentRunStatus.UNDERSTANDING);
            AgentBusinessOrchestrator.HandlingOutcome businessOutcome =
                    businessOrchestrator.handle(userId, runId, sanitizedMessage,
                            transientExactMobile, locationContextId, androidAccessToken, modelGateway);
            if (businessOutcome != null && businessOutcome.handled()) {
                return;
            }
            boolean pendingTransferPaused = businessOutcome != null
                    && businessOutcome.pendingTransferPaused();
            if (intentClassifier.classify(sanitizedMessage)
                    == AgentIntentClassifier.Intent.CONTROLLED_BUSINESS) {
                runs.failRun(
                        userId, runId, "AGENT_TOOL_FLOW_NOT_READY",
                        "该业务需要权威工具，当前实现尚未接通，请使用对应快捷入口");
                return;
            }

            List<ModelGateway.ContextMessage> history = conversationHistory(userId, runId);
            if (memoryConversation.recall(userId, runId, sanitizedMessage)) {
                return;
            }
            MemoryConversationService.ProposalResult memoryResult =
                    memoryConversation.propose(userId, runId, sanitizedMessage, history);
            if (memoryResult == MemoryConversationService.ProposalResult.PROPOSED) return;
            if (memoryResult == MemoryConversationService.ProposalResult.NOT_SAVED) {
                runs.completeTextRun(userId, runId,
                        withPendingTransferReminder(
                                "我会在当前会话中参考这条信息，但尚未保存到长期记忆。请确认保存卡片，或在个人中心的“记忆”中手动添加。",
                                pendingTransferPaused));
                return;
            }

            StringBuilder completed = new StringBuilder();
            StringBuilder pending = new StringBuilder();
            modelGateway.streamText(
                    new ModelGateway.ModelRequest(
                            pendingTransferPaused
                                    ? SYSTEM_PROMPT + PAUSED_TRANSFER_SYSTEM_PROMPT : SYSTEM_PROMPT,
                            history,
                            sanitizedMessage, memoryContext.relevantMemories(userId, sanitizedMessage), "v2"),
                    delta -> {
                        completed.append(delta);
                        pending.append(delta);
                        if (pending.length() >= 32) {
                            runs.appendEvent(userId, runId, "message.delta", Map.of("text", pending.toString()));
                            pending.setLength(0);
                        }
                    });
            if (!pending.isEmpty()) {
                runs.appendEvent(userId, runId, "message.delta", Map.of("text", pending.toString()));
            }
            if (completed.isEmpty()) {
                throw new ModelGatewayException(
                        "AGENT_MODEL_EMPTY_RESPONSE", "模型未返回有效内容，请稍后重试");
            }
            if (pendingTransferPaused) {
                completed.append(PENDING_TRANSFER_REMINDER);
                pending.append(PENDING_TRANSFER_REMINDER);
                runs.appendEvent(userId, runId, "message.delta", Map.of("text", pending.toString()));
                pending.setLength(0);
            }
            runs.completeTextRun(userId, runId, completed.toString());
        } catch (ModelGatewayException exception) {
            runs.failRun(userId, runId, exception.code(), exception.getMessage());
        } catch (AgentApplicationException exception) {
            if (!"AGENT_RUN_STATE_CONFLICT".equals(exception.code())) {
                String reply = conversationalBusinessReply(exception.code());
                if (reply == null) {
                    runs.failRun(userId, runId, exception.code(), exception.getMessage());
                } else {
                    runs.completeBusinessReplyRun(userId, runId, exception.code(), reply);
                }
            }
        } catch (RuntimeException exception) {
            runs.failRun(userId, runId, "AGENT_INTERNAL_ERROR", "AI 任务处理失败，请稍后重试");
        }
    }

    private static String conversationalBusinessReply(String code) {
        return switch (code) {
            case "AGENT_CONTACT_NOT_FOUND" ->
                    "未在你的好友中找到该收款人，请核对好友昵称或完整实名，也可以输入对方完整手机号。";
            case "TRANSFER_RECIPIENT_NOT_FOUND" ->
                    "该手机号尚未注册 MiniPay，请核对号码后再试。";
            default -> null;
        };
    }

    private static String withPendingTransferReminder(String text, boolean pausedTransfer) {
        return pausedTransfer ? text + PENDING_TRANSFER_REMINDER : text;
    }

    private List<ModelGateway.ContextMessage> conversationHistory(UUID userId, UUID runId) {
        var run = runs.getRun(userId, runId);
        List<AiMessage> newest = repository.listMessages(userId, run.conversationId(), null, 24);
        List<ModelGateway.ContextMessage> selectedNewest = new ArrayList<>();
        int remaining = 8_000;
        for (AiMessage message : newest) {
            if (runId.equals(message.runId()) && message.role() == AiMessage.Role.USER) continue;
            if (message.role() == AiMessage.Role.SYSTEM || message.contentText().isBlank()) continue;
            String text = message.contentText();
            int length = text.codePointCount(0, text.length());
            if (length > remaining) continue;
            selectedNewest.add(new ModelGateway.ContextMessage(
                    message.role() == AiMessage.Role.USER
                            ? ModelGateway.ContextMessage.Role.USER
                            : ModelGateway.ContextMessage.Role.ASSISTANT,
                    text));
            remaining -= length;
            if (selectedNewest.size() == 12) break;
        }
        Collections.reverse(selectedNewest);
        return List.copyOf(selectedNewest);
    }
}
