package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.AgentBusinessGateway;
import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.domain.model.ai.AgentRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentPendingTransferRoutingTest {
    private static final UUID USER_ID =
            UUID.fromString("0198f600-0000-7000-8000-000000000001");
    private static final UUID RUN_ID =
            UUID.fromString("0198f600-0000-7000-8000-000000000002");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0198f600-0000-7000-8000-000000000003");

    @Test
    void pausesPendingTransferForUnrelatedConversation() {
        Fixture fixture = fixture(Map.of(
                "awaitingFields", List.of("recipient", "amount"),
                "lastPromptType", "RECIPIENT_AND_AMOUNT"));
        when(fixture.model.classifyPendingTransferTurn(any()))
                .thenReturn(ModelGateway.PendingTransferTurn.NEW_TOPIC);

        AgentBusinessOrchestrator.HandlingOutcome outcome = fixture.orchestrator.handle(
                USER_ID, RUN_ID, "今天天气不错", null, "android-token", fixture.model);

        assertThat(outcome.handled()).isFalse();
        assertThat(outcome.pendingTransferPaused()).isTrue();
        verifyNoInteractions(fixture.gateway);
        verify(fixture.taskStates, never()).clear(any(), any(), any());
    }

    @Test
    void classificationFailureDefaultsToNewTopic() {
        Fixture fixture = fixture(Map.of("awaitingFields", List.of("recipient")));
        when(fixture.model.classifyPendingTransferTurn(any())).thenReturn(null);

        AgentBusinessOrchestrator.HandlingOutcome outcome = fixture.orchestrator.handle(
                USER_ID, RUN_ID, "我记不起来了", null, "android-token", fixture.model);

        assertThat(outcome).isEqualTo(new AgentBusinessOrchestrator.HandlingOutcome(false, true));
        verifyNoInteractions(fixture.gateway);
    }

    @Test
    void explicitCancellationClearsOnlyConversationTransferTask() {
        Fixture fixture = fixture(Map.of("awaitingFields", List.of("recipient")));

        AgentBusinessOrchestrator.HandlingOutcome outcome = fixture.orchestrator.handle(
                USER_ID, RUN_ID, "不转了", null, "android-token", fixture.model);

        assertThat(outcome.handled()).isTrue();
        verify(fixture.taskStates).clear(USER_ID, CONVERSATION_ID, "transfer");
        verify(fixture.runs).completeBusinessReplyRun(
                USER_ID, RUN_ID, "AGENT_TRANSFER_CANCELLED", "已取消刚才未完成的转账。");
        verifyNoInteractions(fixture.gateway);
    }

    private static Fixture fixture(Map<String, Object> slots) {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(CONVERSATION_ID);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        when(taskStates.findLatest(eq(USER_ID), eq(CONVERSATION_ID), any(Instant.class)))
                .thenReturn(Optional.of(new AgentTaskStateStore.PendingTask(
                        UUID.randomUUID(), "transfer", slots)));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        ModelGateway model = mock(ModelGateway.class);
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, mock(DelegatedAuthorizationService.class), gateway, taskStates, "CN-SH-PD");
        return new Fixture(runs, taskStates, gateway, model, orchestrator);
    }

    private record Fixture(
            AgentRunApplicationService runs,
            AgentTaskStateStore taskStates,
            AgentBusinessGateway gateway,
            ModelGateway model,
            AgentBusinessOrchestrator orchestrator) {}
}
