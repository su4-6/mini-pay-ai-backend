package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.AgentBusinessGateway;
import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.application.port.DelegatedTokenProvider;
import com.minipay.agent.domain.model.ai.AgentRun;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentBillQueryTest {
    private static final UUID USER_ID = UUID.fromString("0198f500-0000-7000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("0198f500-0000-7000-8000-000000000002");

    @Test
    void mapsChineseExpenseFilterToWalletExpenseDirection() {
        assertDirection("查看本月支出账单", "EXPENSE");
    }

    @Test
    void mapsChineseIncomeFilterToWalletIncomeDirection() {
        assertDirection("查看本月收入账单", "INCOME");
    }

    @Test
    void mapsColloquialTodaySpendQuestionToAuthoritativeAggregate() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(
                UUID.fromString("0198f500-0000-7000-8000-000000000003"));
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "wallet.aggregateBills"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "delegated-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("wallet.agent.bill.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.aggregateBills(eq("delegated-token"), any(), any()))
                .thenReturn(Map.of("expenseCent", 2_000L));
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, mock(AgentTaskStateStore.class), "CN-SH-PD");

        orchestrator.handle(USER_ID, RUN_ID, "今日消费多少", null, "android-token");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(gateway).aggregateBills(eq("delegated-token"), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(
                LocalDate.now(ZoneId.of("Asia/Shanghai"))
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant());
        verify(runs).completeStructuredRun(eq(USER_ID), eq(RUN_ID), eq("bill.summary.card"),
                eq("wallet.bill-summary"), any(), any());
    }

    private void assertDirection(String message, String expectedDirection) {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(
                UUID.fromString("0198f500-0000-7000-8000-000000000003"));
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "wallet.listBills"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "delegated-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("wallet.agent.bill.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.listBills(eq("delegated-token"), any(), any(), eq(expectedDirection),
                eq(null), eq(null), eq(1), eq(20))).thenReturn(Map.of("items", java.util.List.of()));
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, mock(AgentTaskStateStore.class), "CN-SH-PD");

        orchestrator.handle(USER_ID, RUN_ID, message, null, "android-token");

        verify(gateway).listBills(eq("delegated-token"), any(), any(), eq(expectedDirection),
                eq(null), eq(null), eq(1), eq(20));
    }
}
