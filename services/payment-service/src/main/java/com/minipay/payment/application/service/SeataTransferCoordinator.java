package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.TransferOrderRow;
import java.util.UUID;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.springframework.stereotype.Service;

/** Starts the Seata global transaction from a separate Spring proxy. */
@Service
public class SeataTransferCoordinator {
    private final PaymentRepository repository;
    private final WalletInternalClient wallet;
    private final IdentityInternalClient identity;

    public SeataTransferCoordinator(
            PaymentRepository repository,
            WalletInternalClient wallet,
            IdentityInternalClient identity) {
        this.repository = repository;
        this.wallet = wallet;
        this.identity = identity;
    }

    @GlobalTransactional(
            name = "wallet-transfer-v1",
            timeoutMills = 10_000,
            rollbackFor = Exception.class)
    public void execute(UUID transferId, String paymentAuthToken, String deviceId) {
        String xid = RootContext.getXID();
        if (xid == null || xid.isBlank()) {
            throw new PaymentProblemException(
                    "DISTRIBUTED_TRANSACTION_UNAVAILABLE",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
        TransferOrderRow order = repository.findTransferOrderByTransferId(transferId)
                .orElseThrow();
        if (order.authorizationId() == null) {
            if (paymentAuthToken == null || paymentAuthToken.isBlank()) {
                throw new IllegalStateException("Transfer payment authorization is missing");
            }
            UUID authorizationId = identity.verifyAndConsume(
                    paymentAuthToken,
                    order.payerUserId(),
                    "TRANSFER_INTENT",
                    order.intentId(),
                    order.amountCent(),
                    deviceId);
            repository.authorizeTransfer(order.transferId(), authorizationId);
            order = repository.findTransferOrderByTransferId(transferId).orElseThrow();
        }
        PaymentRepository.TransferIntentRow intent = repository.findTransferIntent(
                order.payerUserId(), order.intentId()).orElseThrow();
        if ("PENDING_CONFIRMATION".equals(intent.status())) {
            repository.confirmTransferIntent(intent.intentId());
        }
        repository.bindTransferXid(transferId, xid);

        if (!wallet.tryDebit(
                order.payerUserId(),
                order.transferNo(),
                intent.source(),
                order.payerAccountId(),
                order.receiverUserId(),
                order.amountCent(),
                repository.transferAnnualLimitMode(order.transferId()))) {
            throw new TransferBranchRejectedException("INSUFFICIENT_BALANCE");
        }
        if (!wallet.tryCredit(
                order.receiverUserId(),
                order.transferNo(),
                intent.source(),
                order.receiverAccountId(),
                order.payerUserId(),
                order.amountCent())) {
            throw new TransferBranchRejectedException("RECEIVER_WALLET_REJECTED");
        }
    }

    public GlobalStatus status(String xid) throws TransactionException {
        return GlobalTransactionContext.reload(xid).getStatus();
    }

    public static final class TransferBranchRejectedException extends RuntimeException {
        private final String failureCode;

        TransferBranchRejectedException(String failureCode) {
            super(failureCode);
            this.failureCode = failureCode;
        }

        public String failureCode() {
            return failureCode;
        }
    }
}
