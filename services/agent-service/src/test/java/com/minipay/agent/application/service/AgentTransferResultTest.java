package com.minipay.agent.application.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minipay.agent.application.port.AgentBusinessGateway;
import com.minipay.agent.application.port.DelegatedTokenProvider;
import com.minipay.agent.application.port.AgentTaskStateStore;
import com.minipay.agent.application.port.ModelGateway;
import com.minipay.agent.domain.model.ai.AgentRun;
import com.minipay.agent.domain.model.ai.AgentRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentTransferResultTest {
    private static final UUID USER_ID = UUID.fromString("0198f200-0000-7000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("0198f200-0000-7000-8000-000000000002");
    private static final UUID TRANSFER_ID = UUID.fromString("0198f200-0000-7000-8000-000000000003");

    @Test
    void writesAuthoritativeCompletedTransferBackToConversation() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "payment.getTransfer"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "delegated-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("payment.agent.transfer.read")));
        Map<String, Object> result = Map.of(
                "transferId", TRANSFER_ID,
                "amountCent", 5_000L,
                "status", "SUCCEEDED");
        when(gateway.transferOrder("delegated-token", TRANSFER_ID)).thenReturn(result);
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, mock(AgentTaskStateStore.class), "CN-SH-PD");

        orchestrator.continueAction(USER_ID, RUN_ID, new AgentBusinessOrchestrator.ActionCommand(
                "GET_TRANSFER", null, null, null, null, null, null, null, TRANSFER_ID, null),
                "android-token");

        verify(gateway).transferOrder("delegated-token", TRANSFER_ID);
        verify(runs).completeStructuredRun(eq(USER_ID), eq(RUN_ID),
                eq("transfer.result.card"), eq("payment.transfer-order"), eq(result),
                eq("这是 Payment 返回的权威转账结果。"));
    }

    @Test
    void completesNativeTransferHandoffWithoutQueryingOrWritingResultMessage() {
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, mock(AgentTaskStateStore.class), "CN-SH-PD");

        orchestrator.continueAction(USER_ID, RUN_ID, new AgentBusinessOrchestrator.ActionCommand(
                "COMPLETE_NATIVE_TRANSFER", null, null, null, null, null, null, null,
                TRANSFER_ID, null), "android-token");

        verify(runs).completeSilentRun(USER_ID, RUN_ID);
        verify(gateway, never()).transferOrder(any(), any());
        verify(runs, never()).completeStructuredRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptsBareContactAliasWhileCompletingPendingTransferSlots() {
        UUID conversationId = UUID.fromString("0198f200-0000-7000-8000-000000000004");
        UUID recipientId = UUID.fromString("0198f200-0000-7000-8000-000000000005");
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(conversationId);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        when(taskStates.findLatest(eq(USER_ID), eq(conversationId), any()))
                .thenReturn(Optional.of(new AgentTaskStateStore.PendingTask(
                        UUID.randomUUID(), "transfer", Map.of())));
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "contact.resolveExactFriend"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "contact-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("agent.contact.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.resolveExactFriend("contact-token", "小明"))
                .thenReturn(List.of(Map.of(
                        "recipientUserId", recipientId.toString(),
                        "nickname", "小明", "phoneMasked", "138****0000", "verified", false)));
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, taskStates, "CN-SH-PD");
        ModelGateway model = mock(ModelGateway.class);
        when(model.classifyPendingTransferTurn(any()))
                .thenReturn(ModelGateway.PendingTransferTurn.TRANSFER_SLOT);

        orchestrator.handle(USER_ID, RUN_ID, "小明", null, "android-token", model);

        verify(taskStates).save(eq(RUN_ID), eq("transfer"),
                argThat(slots -> recipientId.toString().equals(slots.get("recipientUserId"))), any());
        verify(runs).requestInput(eq(USER_ID), eq(RUN_ID), eq("transfer"),
                argThat((Object missing) -> missing instanceof Map<?, ?> values
                        && values.containsKey("amount") && !values.containsKey("recipient")), any());
    }

    @Test
    void resolvesNicknameFromDirectTransferExpressionBeforeAskingOnlyForAmount() {
        UUID conversationId = UUID.fromString("0198f200-0000-7000-8000-000000000006");
        UUID recipientId = UUID.fromString("0198f200-0000-7000-8000-000000000007");
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(conversationId);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        when(taskStates.findLatest(eq(USER_ID), eq(conversationId), any()))
                .thenReturn(Optional.empty());
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "contact.resolveExactFriend"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "contact-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("agent.contact.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.resolveExactFriend("contact-token", "牛新元"))
                .thenReturn(List.of(Map.of(
                        "recipientUserId", recipientId.toString(),
                        "nickname", "牛新元", "phoneMasked", "138****0000", "verified", true)));
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, taskStates, "CN-SH-PD");

        orchestrator.handle(USER_ID, RUN_ID, "转账给牛新元", null, "android-token");

        verify(gateway).resolveExactFriend("contact-token", "牛新元");
        verify(taskStates).save(eq(RUN_ID), eq("transfer"),
                argThat(slots -> recipientId.toString().equals(slots.get("recipientUserId"))), any());
        verify(runs).requestInput(eq(USER_ID), eq(RUN_ID), eq("transfer"),
                argThat((Object missing) -> missing instanceof Map<?, ?> values
                        && values.containsKey("amount") && !values.containsKey("recipient")), any());
    }

    @Test
    void resolvesLeadingFriendNameAndAmountAsStandaloneTransferExpression() {
        UUID conversationId = UUID.fromString("0198f200-0000-7000-8000-000000000008");
        UUID recipientId = UUID.fromString("0198f200-0000-7000-8000-000000000009");
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(conversationId);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        when(taskStates.findLatest(eq(USER_ID), eq(conversationId), any()))
                .thenReturn(Optional.empty());
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool(eq("android-token"), eq(RUN_ID), any()))
                .thenAnswer(invocation -> new DelegatedTokenProvider.DelegatedToken(
                        invocation.getArgument(2) + "-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("agent.contact.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.resolveExactFriend("contact.resolveExactFriend-token", "牛新元"))
                .thenReturn(List.of(Map.of(
                        "recipientUserId", recipientId.toString(),
                        "nickname", "牛新元", "phoneMasked", "138****0000", "verified", true)));
        Map<String, Object> intent = Map.of("intentId", TRANSFER_ID.toString(), "amountCent", 10_000L);
        when(gateway.prepareTransfer(
                eq("payment.prepareTransfer-token"), eq(RUN_ID + ":transfer"),
                eq(recipientId), eq(10_000L), eq(null))).thenReturn(intent);
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, taskStates, "CN-SH-PD");

        orchestrator.handle(USER_ID, RUN_ID, "牛新元 100元", null, "android-token");

        verify(gateway).resolveExactFriend("contact.resolveExactFriend-token", "牛新元");
        verify(gateway).prepareTransfer(
                "payment.prepareTransfer-token", RUN_ID + ":transfer", recipientId, 10_000L, null);
        verify(runs).waitForConfirmation(eq(USER_ID), eq(RUN_ID), eq("transfer.card"),
                eq("payment.transfer-intent"), any(), any());
    }

    @Test
    void keepsPreparedTransferExecutingUntilConfirmationCardIsWritten() {
        UUID conversationId = UUID.fromString("0198f200-0000-7000-8000-000000000010");
        UUID recipientId = UUID.fromString("0198f200-0000-7000-8000-000000000011");
        UUID traceId = UUID.fromString("0198f200-0000-7000-8000-000000000012");
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(conversationId);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        when(runs.startToolTrace(USER_ID, RUN_ID, "payment.prepareTransfer", "W1"))
                .thenReturn(traceId);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        Map<String, Object> pendingSlots = Map.of(
                "recipientUserId", recipientId.toString(),
                "nickname", "friend",
                "phoneMasked", "139****0001",
                "verified", true);
        when(taskStates.findLatest(eq(USER_ID), eq(conversationId), any()))
                .thenReturn(Optional.of(new AgentTaskStateStore.PendingTask(
                        UUID.randomUUID(), "transfer", pendingSlots)));
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "payment.prepareTransfer"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "payment-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("payment.agent.transfer.prepare")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        Map<String, Object> intent = Map.of(
                "intentId", TRANSFER_ID.toString(), "amountCent", 100L);
        when(gateway.prepareTransfer(
                "payment-token", RUN_ID + ":transfer", recipientId, 100L, null))
                .thenReturn(intent);
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, taskStates, "CN-SH-PD");

        orchestrator.handle(USER_ID, RUN_ID, "1 元", null, "android-token");

        verify(runs).completeToolTrace(
                USER_ID, RUN_ID, traceId, "payment.prepareTransfer", "SUCCEEDED");
        verify(runs, never()).transition(USER_ID, RUN_ID, AgentRunStatus.UNDERSTANDING);
        verify(runs).waitForConfirmation(eq(USER_ID), eq(RUN_ID), eq("transfer.card"),
                eq("payment.transfer-intent"), any(), any());
    }

    @Test
    void returnsStableNotFoundErrorWhenExactFriendDoesNotMatch() {
        UUID conversationId = UUID.fromString("0198f200-0000-7000-8000-000000000013");
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(conversationId);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "contact.resolveExactFriend"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "contact-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("agent.contact.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.resolveExactFriend("contact-token", "叶顺光")).thenReturn(List.of());
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, mock(AgentTaskStateStore.class), "CN-SH-PD");

        assertThatThrownBy(() -> orchestrator.handle(
                USER_ID, RUN_ID, "我要给叶顺光转账1元", null, "android-token"))
                .isInstanceOf(AgentApplicationException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((AgentApplicationException) error).code())
                        .isEqualTo("AGENT_CONTACT_NOT_FOUND"));
    }

    @Test
    void requiresSelectionWhenNicknameOrLegalNameMatchesMultipleFriends() {
        UUID conversationId = UUID.fromString("0198f200-0000-7000-8000-000000000014");
        AgentRunApplicationService runs = mock(AgentRunApplicationService.class);
        AgentRun run = mock(AgentRun.class);
        when(run.conversationId()).thenReturn(conversationId);
        when(runs.getRun(USER_ID, RUN_ID)).thenReturn(run);
        AgentTaskStateStore taskStates = mock(AgentTaskStateStore.class);
        when(taskStates.findLatest(eq(USER_ID), eq(conversationId), any())).thenReturn(Optional.empty());
        DelegatedAuthorizationService authorization = mock(DelegatedAuthorizationService.class);
        when(authorization.exchangeForTool("android-token", RUN_ID, "contact.resolveExactFriend"))
                .thenReturn(new DelegatedTokenProvider.DelegatedToken(
                        "contact-token", "Bearer", Instant.now().plusSeconds(60),
                        Set.of("agent.contact.read")));
        AgentBusinessGateway gateway = mock(AgentBusinessGateway.class);
        when(gateway.resolveExactFriend("contact-token", "张三")).thenReturn(List.of(
                Map.of("recipientUserId", UUID.randomUUID().toString(), "nickname", "张三",
                        "phoneMasked", "138****0001", "verified", true),
                Map.of("recipientUserId", UUID.randomUUID().toString(), "nickname", "老张",
                        "phoneMasked", "139****0002", "verified", true)));
        AgentBusinessOrchestrator orchestrator = new AgentBusinessOrchestrator(
                runs, authorization, gateway, taskStates, "CN-SH-PD");

        orchestrator.handle(USER_ID, RUN_ID, "转给张三1元", null, "android-token");

        verify(runs).waitForInputCard(eq(USER_ID), eq(RUN_ID), eq("choice.card"),
                eq("agent.contact-selection"), any(), any());
        verify(gateway, never()).prepareTransfer(any(), any(), any(), anyLong(), any());
    }
}
