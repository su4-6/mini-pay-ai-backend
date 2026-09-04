package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransferRecoveryJob {
    private static final Logger LOG = LoggerFactory.getLogger(TransferRecoveryJob.class);
    private final PaymentRepository repository;
    private final TransferService transfers;

    public TransferRecoveryJob(
            PaymentRepository repository,
            TransferService transfers) {
        this.repository = repository;
        this.transfers = transfers;
    }

    @Scheduled(fixedDelayString = "${minipay.payment.transfer-recovery-delay-ms:2000}")
    public void recover() {
        for (UUID transferId : repository.findRecoverableTransferIds(20)) {
            try {
                transfers.resume(transferId);
            } catch (RuntimeException exception) {
                repository.scheduleTransferRecovery(
                        transferId, "RECOVERY_COORDINATOR_UNAVAILABLE");
                LOG.warn("Transfer {} remains PROCESSING; recovery will retry", transferId,
                        exception);
            }
        }
    }
}
