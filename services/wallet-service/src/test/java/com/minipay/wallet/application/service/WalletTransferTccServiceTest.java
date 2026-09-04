package com.minipay.wallet.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.minipay.wallet.infrastructure.persistence.WalletRepository;
import com.minipay.wallet.infrastructure.persistence.AnnualOutflowRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

class WalletTransferTccServiceTest {
    @Test
    void emptyRollbackIsIdempotentAndDelegatesFencingToSeata() {
        WalletRepository repository = mock(WalletRepository.class);
        WalletTransferTccService service = new WalletTransferTccService(
                repository, mock(AnnualOutflowRepository.class), mock(ApplicationEventPublisher.class));
        String xid = "TX-empty-rollback";
        when(repository.lockFreeze(xid, 1L)).thenReturn(Optional.empty());

        assertThat(service.cancelDebit(xid, 1L)).isTrue();
    }

    @Test
    void insufficientBalanceNeverCreatesAFreeze() {
        WalletRepository repository = mock(WalletRepository.class);
        WalletTransferTccService service = new WalletTransferTccService(
                repository, mock(AnnualOutflowRepository.class), mock(ApplicationEventPublisher.class));
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(repository.lockAccountById(accountId)).thenReturn(Optional.of(
                new WalletRepository.AccountRow(
                        accountId,
                        ownerId,
                        99L,
                        0L,
                        "CNY",
                        "ACTIVE")));

        assertThat(service.tryDebit(
                "TX-low-balance", 1L, "T002", "FORM", ownerId, accountId, 100L,
                "EXEMPT_ACTIVE_CARD")).isFalse();
    }

    @Test
    void accountIdCannotBeUsedForAnotherOwner() {
        WalletRepository repository = mock(WalletRepository.class);
        WalletTransferTccService service = new WalletTransferTccService(
                repository, mock(AnnualOutflowRepository.class), mock(ApplicationEventPublisher.class));
        UUID accountId = UUID.randomUUID();
        when(repository.lockAccountById(accountId)).thenReturn(Optional.of(
                new WalletRepository.AccountRow(
                        accountId, UUID.randomUUID(), 10_000L, 0L, "CNY", "ACTIVE")));

        assertThat(service.tryDebit(
                "TX-owner", 1L, "T003", "FORM", UUID.randomUUID(), accountId, 100L,
                "EXEMPT_ACTIVE_CARD")).isFalse();
    }

    @Test
    void personalCollectionCreditPublishesOnlyThePostCommitReceiptPayload() {
        WalletRepository repository = mock(WalletRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WalletTransferTccService service = new WalletTransferTccService(
                repository, mock(AnnualOutflowRepository.class), events);
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(repository.lockPendingCredit("TX-receipt", 2L)).thenReturn(Optional.of(
                new WalletRepository.BranchRow(UUID.randomUUID(), "T100",
                        "PERSONAL_COLLECTION_CODE", accountId, 88L, "TRY",
                        "EXEMPT_ACTIVE_CARD", null, 0L)));
        when(repository.lockAccountById(accountId)).thenReturn(Optional.of(
                new WalletRepository.AccountRow(accountId, ownerId, 100L, 0L, "CNY", "ACTIVE")));

        assertThat(service.confirmCredit("TX-receipt", 2L)).isTrue();

        ArgumentCaptor<CollectionReceiptReady> receipt = ArgumentCaptor.forClass(CollectionReceiptReady.class);
        verify(events).publishEvent(receipt.capture());
        assertThat(receipt.getValue().ownerId()).isEqualTo(ownerId);
        assertThat(receipt.getValue().source()).isEqualTo("PERSONAL_COLLECTION_CODE");
        assertThat(receipt.getValue().amountCent()).isEqualTo(88L);
    }
}
