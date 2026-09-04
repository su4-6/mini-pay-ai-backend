package com.minipay.payment.application.service;

import com.minipay.payment.domain.model.TransferIntent;
import com.minipay.payment.domain.model.TransferOrder;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.client.WalletInternalClient.ResolvedAccount;
import com.minipay.payment.infrastructure.client.WalletInternalClient.TransferOutcome;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.TransferIntentRow;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.TransferOrderRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.core.model.GlobalStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
    private static final Set<String> SOURCES =
            Set.of("FORM", "AI", "PERSONAL_COLLECTION_CODE");
    private final PaymentRepository repository;
    private final WalletInternalClient wallet;
    private final DomainEventWriter events;
    private final SeataTransferCoordinator coordinator;

    public TransferService(
            PaymentRepository repository,
            WalletInternalClient wallet,
            DomainEventWriter events,
            SeataTransferCoordinator coordinator) {
        this.repository = repository;
        this.wallet = wallet;
        this.events = events;
        this.coordinator = coordinator;
    }

    public TransferIntent create(
            UUID payerUserId,
            String idempotencyKey,
            UUID receiverUserId,
            long amountCent,
            String remark,
            String source) {
        validateCreate(payerUserId, receiverUserId, amountCent, remark, source);
        String scopedKey = RequestSupport.scopedIdempotencyKey(
                payerUserId, idempotencyKey);
        byte[] requestHash = RequestSupport.hash(
                receiverUserId + ":" + amountCent + ":"
                        + (remark == null ? "" : remark) + ":" + source);
        TransferIntentRow existing = repository.findTransferIntentByIdempotency(
                scopedKey).orElse(null);
        if (existing != null) {
            if (!Arrays.equals(existing.requestHash(), requestHash)) {
                throw new PaymentProblemException(
                        "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
            }
            return existing.toPublicModel();
        }
        ResolvedAccount payer = wallet.resolveAccount(payerUserId);
        ResolvedAccount receiver = wallet.resolveAccount(receiverUserId);
        UUID intentId = UuidV7.generate();
        try {
            return repository.insertTransferIntent(
                    intentId,
                    RequestSupport.businessNo("I", intentId),
                    payerUserId,
                    payer.accountId(),
                    receiverUserId,
                    receiver.accountId(),
                    scopedKey,
                    requestHash,
                    amountCent,
                    remark == null || remark.isBlank() ? null : remark.strip(),
                    source,
                    Instant.now().plus(15, ChronoUnit.MINUTES));
        } catch (DuplicateKeyException exception) {
            TransferIntentRow concurrent = repository.findTransferIntentByIdempotency(scopedKey)
                    .orElseThrow(() -> exception);
            if (!Arrays.equals(concurrent.requestHash(), requestHash)) {
                throw new PaymentProblemException(
                        "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
            }
            return concurrent.toPublicModel();
        }
    }

    public TransferIntent cancel(UUID payerUserId, UUID intentId) {
        TransferIntentRow intent = intent(payerUserId, intentId);
        if ("CONFIRMED".equals(intent.status())) {
            throw new PaymentProblemException(
                    "TRANSFER_ALREADY_CONFIRMED", HttpStatus.CONFLICT);
        }
        return repository.cancelTransferIntent(payerUserId, intentId);
    }

    public TransferOrder confirm(
            UUID payerUserId,
            UUID intentId,
            String idempotencyKey,
            String paymentAuthToken,
            String deviceId) {
        TransferIntentRow intent = intent(payerUserId, intentId);
        if ("CANCELLED".equals(intent.status()) || "EXPIRED".equals(intent.status())) {
            throw new PaymentProblemException(
                    "TRANSFER_INTENT_NOT_CONFIRMABLE", HttpStatus.CONFLICT);
        }
        if (intent.expiresAt().isBefore(Instant.now())
                && "PENDING_CONFIRMATION".equals(intent.status())) {
            repository.expireTransferIntent(intentId);
            throw new PaymentProblemException(
                    "TRANSFER_INTENT_EXPIRED", HttpStatus.CONFLICT);
        }
        TransferOrderRow order = repository.findTransferOrderByIntent(intentId).orElse(null);
        if (order == null) {
            UUID transferId = UuidV7.generate();
            String scopedKey = RequestSupport.scopedIdempotencyKey(
                    payerUserId, idempotencyKey);
            try {
                order = repository.insertTransferOrder(
                        transferId,
                        RequestSupport.businessNo("T", transferId),
                        scopedKey,
                        intent,
                        repository.hasActiveBankCard(payerUserId)
                                ? "EXEMPT_ACTIVE_CARD" : "LIMITED");
                writeTransferEvent(order, "payment.transfer.processing", null);
            } catch (DuplicateKeyException exception) {
                order = repository.findTransferOrderByIntent(intentId)
                        .orElseThrow(() -> exception);
            }
        }
        if (!"PROCESSING".equals(order.status())) {
            return order.toPublicModel();
        }
        return executeOrReconcile(order, paymentAuthToken, deviceId);
    }

    public TransferOrder getOrder(UUID payerUserId, UUID transferId) {
        return repository.findTransferOrder(payerUserId, transferId)
                .orElseThrow(() -> new PaymentProblemException(
                        "TRANSFER_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public TransferOrder resume(UUID transferId) {
        TransferOrderRow order = repository.findTransferOrderByTransferId(transferId)
                .orElseThrow(() -> new PaymentProblemException(
                        "TRANSFER_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"PROCESSING".equals(order.status()) || order.authorizationId() == null) {
            return order.toPublicModel();
        }
        return executeOrReconcile(order, null, null);
    }

    private TransferOrder executeOrReconcile(
            TransferOrderRow initial, String paymentAuthToken, String deviceId) {
        TransferOrderRow order = repository.findTransferOrderByTransferId(initial.transferId())
                .orElseThrow();
        TransferOrder reconciled = reconcile(order, order.recoveryErrorCode());
        if (!"PROCESSING".equals(reconciled.status()) || order.xid() != null) {
            return reconciled;
        }
        if (order.authorizationId() == null
                && (paymentAuthToken == null || paymentAuthToken.isBlank())) {
            return order.toPublicModel();
        }
        if (!repository.claimTransferExecution(order.transferId(), 15)) {
            return repository.findTransferOrderByTransferId(order.transferId())
                    .orElseThrow().toPublicModel();
        }

        String failureHint = null;
        boolean recoveryScheduled = false;
        try {
            coordinator.execute(order.transferId(), paymentAuthToken, deviceId);
        } catch (RuntimeException exception) {
            PaymentProblemException problem = findCause(
                    exception, PaymentProblemException.class);
            if (problem != null
                    && "PAYMENT_AUTHORIZATION_INVALID".equals(problem.code())) {
                repository.releaseTransferExecution(order.transferId());
                throw problem;
            }
            SeataTransferCoordinator.TransferBranchRejectedException rejected =
                    findCause(exception,
                            SeataTransferCoordinator.TransferBranchRejectedException.class);
            failureHint = rejected == null
                    ? "TCC_EXECUTION_INTERRUPTED" : rejected.failureCode();
            TransferOrderRow failedAttempt = repository
                    .findTransferOrderByTransferId(order.transferId()).orElseThrow();
            repository.scheduleTransferRecovery(order.transferId(), failureHint);
            recoveryScheduled = true;
            if (failedAttempt.xid() == null) {
                throw new PaymentProblemException(
                        "DISTRIBUTED_TRANSACTION_UNAVAILABLE",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
        }

        TransferOrderRow latest = repository.findTransferOrderByTransferId(order.transferId())
                .orElseThrow();
        TransferOrder result = reconcile(latest, failureHint);
        if ("PROCESSING".equals(result.status()) && !recoveryScheduled) {
            repository.releaseTransferExecution(order.transferId());
        }
        return result;
    }

    private TransferOrder reconcile(TransferOrderRow order, String failureHint) {
        if (!"PROCESSING".equals(order.status())) {
            return order.toPublicModel();
        }
        TransferOutcome outcome = wallet.transferOutcome(
                order.transferNo(), order.payerAccountId(), order.receiverAccountId());
        if ("CONFIRMED".equals(outcome.debitStatus())
                && "CONFIRMED".equals(outcome.creditStatus())) {
            UUID transferId = order.transferId();
            writeTransferEvent(
                    order,
                    "payment.transfer.succeeded",
                    null,
                    () -> repository.completeTransferOrder(
                            transferId, "SUCCEEDED", null));
        } else if (isRolledBack(order, outcome, failureHint)) {
            String code = failureHint != null
                    ? failureHint
                    : order.recoveryErrorCode() != null
                    ? order.recoveryErrorCode()
                    : "DISTRIBUTED_TRANSACTION_ROLLED_BACK";
            UUID transferId = order.transferId();
            writeTransferEvent(
                    order,
                    "payment.transfer.failed",
                    code,
                    () -> repository.completeTransferOrder(
                            transferId, "FAILED", code));
        }
        return repository.findTransferOrderByTransferId(order.transferId())
                .orElseThrow().toPublicModel();
    }

    private boolean isRolledBack(
            TransferOrderRow order, TransferOutcome outcome, String failureHint) {
        boolean debitGone = "CANCELLED".equals(outcome.debitStatus())
                || "NOT_FOUND".equals(outcome.debitStatus());
        boolean creditGone = "CANCELLED".equals(outcome.creditStatus())
                || "NOT_FOUND".equals(outcome.creditStatus());
        boolean cancellationObserved = "CANCELLED".equals(outcome.debitStatus())
                || "CANCELLED".equals(outcome.creditStatus());
        if (!debitGone || !creditGone) return false;
        if (cancellationObserved || failureHint != null) return true;
        if (order.xid() == null) return false;
        try {
            GlobalStatus status = coordinator.status(order.xid());
            return status == GlobalStatus.Rollbacked
                    || status == GlobalStatus.TimeoutRollbacked;
        } catch (TransactionException exception) {
            return false;
        }
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    private TransferIntentRow intent(UUID payerUserId, UUID intentId) {
        return repository.findTransferIntent(payerUserId, intentId)
                .orElseThrow(() -> new PaymentProblemException(
                        "TRANSFER_INTENT_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private void validateCreate(
            UUID payerUserId,
            UUID receiverUserId,
            long amountCent,
            String remark,
            String source) {
        if (payerUserId.equals(receiverUserId)) {
            throw new PaymentProblemException(
                    "SELF_TRANSFER_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (amountCent < 1 || amountCent > 1_000_000L) {
            throw new PaymentProblemException(
                    "AMOUNT_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (remark != null && remark.length() > 50) {
            throw new PaymentProblemException("REMARK_TOO_LONG", HttpStatus.BAD_REQUEST);
        }
        if (!SOURCES.contains(source)) {
            throw new PaymentProblemException(
                    "INVALID_TRANSFER_SOURCE", HttpStatus.BAD_REQUEST);
        }
    }

    private void writeTransferEvent(
            TransferOrderRow order,
            String eventType,
            String failureCode) {
        writeTransferEvent(order, eventType, failureCode, () -> {});
    }

    private void writeTransferEvent(
            TransferOrderRow order,
            String eventType,
            String failureCode,
            Runnable stateChange) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("transferId", order.transferId());
        payload.put("transferNo", order.transferNo());
        payload.put("payerUserId", order.payerUserId());
        payload.put("receiverUserId", order.receiverUserId());
        payload.put("amountCent", order.amountCent());
        payload.put("source", repository.findTransferIntent(order.payerUserId(), order.intentId())
                .map(TransferIntentRow::source).orElse("FORM"));
        payload.put("status", switch (eventType) {
            case "payment.transfer.succeeded" -> "SUCCEEDED";
            case "payment.transfer.failed" -> "FAILED";
            default -> "PROCESSING";
        });
        if (failureCode != null) payload.put("failureCode", failureCode);
        events.writeAfter(
                stateChange,
                eventType,
                "TRANSFER_ORDER",
                order.transferId(),
                payload);
    }
}
