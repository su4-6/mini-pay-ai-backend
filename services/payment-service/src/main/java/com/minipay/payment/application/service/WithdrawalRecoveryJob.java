package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes authorized withdrawals that were interrupted between wallet posting,
 * idempotent bank payout and the final order update.
 */
@Component
public class WithdrawalRecoveryJob {
    private static final Logger LOG = LoggerFactory.getLogger(WithdrawalRecoveryJob.class);
    private final PaymentRepository repository;
    private final WithdrawalService withdrawals;

    public WithdrawalRecoveryJob(PaymentRepository repository, WithdrawalService withdrawals) {
        this.repository = repository;
        this.withdrawals = withdrawals;
    }

    @Scheduled(fixedDelayString = "${minipay.payment.withdrawal-recovery-delay-ms:2000}")
    public void recover() {
        for (UUID withdrawalId : repository.findRecoverableWithdrawalIds(20)) {
            try {
                withdrawals.resume(withdrawalId);
            } catch (RuntimeException exception) {
                LOG.warn("Withdrawal {} remains PROCESSING; recovery will retry", withdrawalId,
                        exception);
            }
        }
    }
}
