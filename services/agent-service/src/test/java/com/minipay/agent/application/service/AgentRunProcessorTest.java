package com.minipay.agent.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class AgentRunProcessorTest {
    private static final UUID USER_ID = UUID.fromString("0198f200-0000-7000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("0198f200-0000-7000-8000-000000000002");

    @Test
    void routesControlledBusinessQuestionsToAuthoritativeOrchestrator() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentBusinessOrchestrator business = mock(AgentBusinessOrchestrator.class);
        ModelGateway model = mock(ModelGateway.class);
        when(business.handle(USER_ID, RUN_ID, "我的余额是多少", null, null, "android-token", model))
                .thenReturn(AgentBusinessOrchestrator.HandlingOutcome.HANDLED);
        AgentRunProcessor processor = new AgentRunProcessor(
                runs, new AgentIntentClassifier(), business, model, mock(AiAgentRepository.class),
                mock(MemoryContextService.class), mock(MemoryConversationService.class),
                mock(ExecutorService.class));

        processor.process(USER_ID, RUN_ID, "我的余额是多少", null, "android-token");

        verify(runs).transition(USER_ID, RUN_ID, AgentRunStatus.UNDERSTANDING);
        verify(business).handle(USER_ID, RUN_ID, "我的余额是多少", null, null, "android-token", model);
        verify(model, never()).streamText(any(), any());
    }

    @Test
    void streamsGeneralConversationAndPersistsCompletedText() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(UUID.fromString("0198f200-0000-7000-8000-000000000003"));
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentBusinessOrchestrator business = mock(AgentBusinessOrchestrator.class);
        ModelGateway model = (request, consumer) -> {
            consumer.accept("你好，");
            consumer.accept("我是 MiniPay 助手。");
        };
        AgentRunProcessor processor = new AgentRunProcessor(
                runs, new AgentIntentClassifier(), business, model, mock(AiAgentRepository.class),
                mock(MemoryContextService.class), mock(MemoryConversationService.class),
                mock(ExecutorService.class));

        processor.process(USER_ID, RUN_ID, "你好", null, "android-token");

        verify(runs).transition(USER_ID, RUN_ID, AgentRunStatus.UNDERSTANDING);
        verify(runs).appendEvent(eq(USER_ID), eq(RUN_ID), eq("message.delta"), any());
        verify(runs).completeTextRun(USER_ID, RUN_ID, "你好，我是 MiniPay 助手。");
    }

    @Test
    void repliesInConversationWhenTransferContactDoesNotExist() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentBusinessOrchestrator business = mock(AgentBusinessOrchestrator.class);
        ModelGateway model = mock(ModelGateway.class);
        when(business.handle(USER_ID, RUN_ID, "转给不存在的人 20 元", null,
                null, "android-token", model)).thenThrow(new AgentApplicationException(
                "AGENT_CONTACT_NOT_FOUND", "downstream detail"));
        AgentRunProcessor processor = new AgentRunProcessor(
                runs, new AgentIntentClassifier(), business, model,
                mock(AiAgentRepository.class), mock(MemoryContextService.class),
                mock(MemoryConversationService.class), mock(ExecutorService.class));

        processor.process(USER_ID, RUN_ID, "转给不存在的人 20 元", null, "android-token");

        verify(runs).completeBusinessReplyRun(USER_ID, RUN_ID, "AGENT_CONTACT_NOT_FOUND",
                "未在你的好友中找到该收款人，请核对好友昵称或完整实名，也可以输入对方完整手机号。");
        verify(runs, never()).failRun(any(), any(), any(), any());
    }

    @Test
    void repliesInConversationWhenMobileIsNotRegistered() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentBusinessOrchestrator business = mock(AgentBusinessOrchestrator.class);
        ModelGateway model = mock(ModelGateway.class);
        when(business.handle(USER_ID, RUN_ID, "转给[MOBILE_EXACT] 20 元", "13900000000",
                null, "android-token", model)).thenThrow(new AgentApplicationException(
                        "TRANSFER_RECIPIENT_NOT_FOUND", "downstream detail"));
        AgentRunProcessor processor = new AgentRunProcessor(
                runs, new AgentIntentClassifier(), business, model,
                mock(AiAgentRepository.class), mock(MemoryContextService.class),
                mock(MemoryConversationService.class), mock(ExecutorService.class));

        processor.process(USER_ID, RUN_ID, "转给[MOBILE_EXACT] 20 元", "13900000000",
                "android-token");

        verify(runs).completeBusinessReplyRun(USER_ID, RUN_ID, "TRANSFER_RECIPIENT_NOT_FOUND",
                "该手机号尚未注册 MiniPay，请核对号码后再试。");
        verify(runs, never()).failRun(any(), any(), any(), any());
    }

    @Test
    void answersNewTopicAndAddsReminderWhenTransferIsPaused() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(
                UUID.fromString("0198f200-0000-7000-8000-000000000003"));
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentBusinessOrchestrator business = mock(AgentBusinessOrchestrator.class);
        ModelGateway model = (request, consumer) -> consumer.accept("我是 MiniPay 助手。");
        when(business.handle(USER_ID, RUN_ID, "你是谁", null, null, "android-token", model))
                .thenReturn(new AgentBusinessOrchestrator.HandlingOutcome(false, true));
        AgentRunProcessor processor = new AgentRunProcessor(
                runs, new AgentIntentClassifier(), business, model, mock(AiAgentRepository.class),
                mock(MemoryContextService.class), mock(MemoryConversationService.class),
                mock(ExecutorService.class));

        processor.process(USER_ID, RUN_ID, "你是谁", null, "android-token");

        verify(runs).completeTextRun(USER_ID, RUN_ID,
                "我是 MiniPay 助手。\n\n另外，刚才的转账还未完成，需要时可以说“继续转账”。");
    }
}
