package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.AgentBusinessGateway;
import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.domain.model.ai.AgentRun;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AgentFoodEntryRoutingTest {
    private static final UUID USER_ID =
            UUID.fromString("0198f700-0000-7000-8000-000000000001");
    private static final UUID RUN_ID =
            UUID.fromString("0198f700-0000-7000-8000-000000000002");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0198f700-0000-7000-8000-000000000003");

    @ParameterizedTest
    @ValueSource(strings = {"我要点外卖", "我要点外卖喝咖啡", "帮我点餐", "想喝奶茶", "买杯咖啡"})
    void completesFoodIntentsWithTrustedEntryCard(String message) {
        Fixture fixture = fixture();

        AgentBusinessOrchestrator.HandlingOutcome outcome = fixture.orchestrator.handle(
                USER_ID, RUN_ID, message, null, "android-token");

        assertThat(outcome).isEqualTo(AgentBusinessOrchestrator.HandlingOutcome.HANDLED);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(fixture.runs).completeStructuredRun(
                eq(USER_ID), eq(RUN_ID), eq("food.entry.card"), eq("commerce.food-entry"),
                payload.capture(), eq("已为你准备好意向外卖入口，点击卡片开始点餐。"));
        assertThat(payload.getValue()).isEqualTo(
                Map.of("service", "food", "destination", "FOOD_ENTRY"));
        verifyNoInteractions(fixture.gateway, fixture.authorization);
    }

    @Test
    void leavesNonFoodIntentForOtherHandlers() {
        Fixture fixture = fixture();

        AgentBusinessOrchestrator.HandlingOutcome outcome = fixture.orchestrator.handle(
                USER_ID, RUN_ID, "今天天气怎么样", null, "android-token");

        assertThat(outcome.handled()).isFalse();
        verifyNoInteractions(fixture.gateway, fixture.authorization);
    }

    private static Fixture fixture() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(CONVERSATION_ID);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        when(taskStates.findLatest(eq(USER_ID), eq(CONVERSATION_ID), any(Instant.class)))
                .thenReturn(Optional.empty());
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, taskStates, "CN-SH-PD");
        return new Fixture(runs, authorization, gateway, orchestrator);
    }

    private record Fixture(
            AgentRunApplicationService runs,
            DelegatedAuthorizationService authorization,
            AgentBusinessGateway gateway,
            AgentBusinessOrchestrator orchestrator) {}
}
