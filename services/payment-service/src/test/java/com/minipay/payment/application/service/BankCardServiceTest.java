package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.application.port.BankGateway;
import com.minipay.payment.domain.model.BankBalance;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.BankCardRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BankCardServiceTest {
    @Test
    void consumesACardAndDeviceBoundAuthorizationBeforeReturningBalance() {
        BankGateway bank = mock(BankGateway.class);
        PaymentRepository repository = mock(PaymentRepository.class);
        IdentityInternalClient identity = mock(IdentityInternalClient.class);
        BankCardService service = new BankCardService(bank, repository, identity);
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        BankCardRow card = new BankCardRow(
                cardId, userId, "SANDBOX_BANK", "provider-token", "沙箱银行",
                "DEBIT", "**** 4020", "4020", "测试用户", "ACTIVE", Instant.now());
        when(repository.findBankCardRow(userId, cardId, false)).thenReturn(Optional.of(card));
        when(bank.balance("provider-token")).thenReturn(
                new BankBalance(null, 100_00L, "CNY", Instant.now(), "沙箱"));

        BankBalance result = service.balance(
                userId, cardId, "one-time-token", "device-1");

        assertThat(result.cardId()).isEqualTo(cardId);
        verify(identity).verifyAndConsume(
                "one-time-token", userId, "BANK_CARD_BALANCE_QUERY", cardId, 0L, "device-1");
        verify(bank).balance("provider-token");
    }

    @Test
    void rejectsInvalidTransactionPaginationBeforeCallingTheBank() {
        BankCardService service = new BankCardService(
                mock(BankGateway.class),
                mock(PaymentRepository.class),
                mock(IdentityInternalClient.class));

        assertThatThrownBy(() -> service.transactions(
                UUID.randomUUID(), UUID.randomUUID(), null, null, 0, 20))
                .isInstanceOf(PaymentProblemException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE_QUERY");
    }
}
