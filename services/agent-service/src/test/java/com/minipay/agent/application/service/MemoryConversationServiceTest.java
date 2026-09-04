package com.minipay.agent.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.application.port.AiAgentRepository;
import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AiMessage;
import com.minipay.agent.domain.model.ai.MemorySetting;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryConversationServiceTest {
    private static final UUID USER_ID = UUID.fromString("0198f400-0000-7000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("0198f400-0000-7000-8000-000000000002");
    private static final UUID CONVERSATION_ID = UUID.fromString("0198f400-0000-7000-8000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("0198f400-0000-7000-8000-000000000004");

    @Test
    void skipsMemoryModelForOrdinaryConversation() {
        MemoryApplicationService memory = mock(MemoryApplicationService.class);
        ModelGateway model = mock(ModelGateway.class);
        MemoryConversationService service = new MemoryConversationService(
                memory,
                new MemoryContentPolicy(),
                model,
                mock(AgentTaskStateStore.class),
                mock(AgentRunApplicationService.class),
                mock(AiAgentRepository.class));

        var proposed = service.propose(USER_ID, RUN_ID, "你好，介绍一下你自己", List.of());

        org.assertj.core.api.Assertions.assertThat(proposed)
                .isEqualTo(MemoryConversationService.ProposalResult.NOT_APPLICABLE);
        verify(memory, never()).setting(any());
        verify(model, never()).classifyMemory(any());
    }

    @Test
    void createsConfirmationCardForAuthorizedLongTermAllergenCandidate() {
        MemoryApplicationService memory = mock(MemoryApplicationService.class);
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        when(memory.setting(USER_ID)).thenReturn(new MemorySetting(
                USER_ID, true, false, true, false, false, false, 1, now, now));
        ModelGateway model = mock(ModelGateway.class);
        when(model.classifyMemory(any())).thenReturn(Optional.of(new ModelGateway.MemoryCandidate(
                true, "ALLERGEN_AVOIDANCE", "对牛奶过敏", true, "我对牛奶过敏")));
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(CONVERSATION_ID);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AiAgentRepository repository = mock(AiAgentRepository.class);
        when(repository.listMessages(USER_ID, CONVERSATION_ID, null, 24)).thenReturn(List.of(
                new AiMessage(MESSAGE_ID, CONVERSATION_ID, RUN_ID, AiMessage.Role.USER,
                        "我对牛奶过敏", null, null, null, 1, now)));
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        MemoryConversationService service = new MemoryConversationService(
                memory, new MemoryContentPolicy(), model, taskStates, runs, repository);

        var proposed = service.propose(USER_ID, RUN_ID, "我对牛奶过敏", List.of());

        org.assertj.core.api.Assertions.assertThat(proposed)
                .isEqualTo(MemoryConversationService.ProposalResult.PROPOSED);
        verify(taskStates).save(eq(RUN_ID), eq("memory-proposal"), any(), any());
        verify(runs).waitForConfirmation(eq(USER_ID), eq(RUN_ID), eq("memory.confirmation"),
                eq("memory.confirmation"), any(), any());
    }

    @Test
    void rejectsTemporaryPreferenceEvenWhenModelMarksItLongTerm() {
        MemoryApplicationService memory = mock(MemoryApplicationService.class);
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        when(memory.setting(USER_ID)).thenReturn(new MemorySetting(
                USER_ID, true, true, false, false, false, false, 1, now, now));
        ModelGateway model = mock(ModelGateway.class);
        when(model.classifyMemory(any())).thenReturn(Optional.of(new ModelGateway.MemoryCandidate(
                true, "FOOD_PREFERENCE", "喜欢吃辣", true, "今天喜欢吃辣")));
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        MemoryConversationService service = new MemoryConversationService(
                memory, new MemoryContentPolicy(), model, taskStates, runs,
                mock(AiAgentRepository.class));

        var proposed = service.propose(USER_ID, RUN_ID, "今天喜欢吃辣", List.of());

        org.assertj.core.api.Assertions.assertThat(proposed)
                .isEqualTo(MemoryConversationService.ProposalResult.NOT_SAVED);
        verify(taskStates, never()).save(any(), any(), any(), any());
        verify(runs, never()).waitForConfirmation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void degradesToNotSavedWhenMemoryClassificationFails() {
        MemoryApplicationService memory = mock(MemoryApplicationService.class);
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        when(memory.setting(USER_ID)).thenReturn(new MemorySetting(
                USER_ID, true, true, false, false, false, false, 1, now, now));
        ModelGateway model = mock(ModelGateway.class);
        when(model.classifyMemory(any())).thenThrow(new IllegalStateException("model unavailable"));
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        MemoryConversationService service = new MemoryConversationService(
                memory, new MemoryContentPolicy(), model, taskStates, runs,
                mock(AiAgentRepository.class));

        var proposed = service.propose(USER_ID, RUN_ID, "记住我喜欢吃辣", List.of());

        org.assertj.core.api.Assertions.assertThat(proposed)
                .isEqualTo(MemoryConversationService.ProposalResult.NOT_SAVED);
        verify(taskStates, never()).save(any(), any(), any(), any());
        verify(runs, never()).waitForConfirmation(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recallsSavedAllergenWithoutCallingTheModel() {
        MemoryApplicationService memory = mock(MemoryApplicationService.class);
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        when(memory.setting(USER_ID)).thenReturn(new MemorySetting(
                USER_ID, true, false, true, false, false, false, 1, now, now));
        when(memory.list(USER_ID, com.minipay.agent.domain.model.ai.MemoryType.ALLERGEN_AVOIDANCE, 20))
                .thenReturn(List.of(new com.minipay.agent.domain.model.ai.MemoryItem(
                        UUID.randomUUID(), USER_ID,
                        com.minipay.agent.domain.model.ai.MemoryType.ALLERGEN_AVOIDANCE,
                        "不能吃坚果", null, null, "ACTIVE", MESSAGE_ID,
                        "CHAT_EXPLICIT", null, 0, now, now)));
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        ModelGateway model = mock(ModelGateway.class);
        MemoryConversationService service = new MemoryConversationService(
                memory, new MemoryContentPolicy(), model, mock(AgentTaskStateStore.class),
                runs, mock(AiAgentRepository.class));

        boolean recalled = service.recall(USER_ID, RUN_ID, "我不能吃什么");
        boolean possessiveRecall = service.recall(USER_ID, RUN_ID, "我的忌口");

        org.assertj.core.api.Assertions.assertThat(recalled).isTrue();
        org.assertj.core.api.Assertions.assertThat(possessiveRecall).isTrue();
        verify(runs, times(2)).completeTextRun(USER_ID, RUN_ID, "你已保存的忌口与过敏原：不能吃坚果。");
        verify(model, never()).classifyMemory(any());
    }
}
