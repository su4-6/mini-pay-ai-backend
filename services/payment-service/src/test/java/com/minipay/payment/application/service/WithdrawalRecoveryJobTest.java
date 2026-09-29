package com.minipay.payment.application.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WithdrawalRecoveryJobTest {
    @Test
    void resumesEveryAuthorizedProcessingWithdrawalAndKeepsScanningAfterFailure() {
        PaymentRepository repository = mock(PaymentRepository.class);
        WithdrawalService withdrawals = mock(WithdrawalService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findRecoverableWithdrawalIds(20)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("temporary")).when(withdrawals).resume(first);

        new WithdrawalRecoveryJob(repository, withdrawals).recover();

        verify(withdrawals).resume(first);
        verify(withdrawals).resume(second);
    }
}
