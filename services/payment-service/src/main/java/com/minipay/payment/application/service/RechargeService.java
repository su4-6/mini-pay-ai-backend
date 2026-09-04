package com.minipay.payment.application.service;

import com.minipay.payment.application.port.BankGateway;
import com.minipay.payment.application.port.BankGateway.BankResult;
import com.minipay.payment.domain.model.RechargeOrder;
import com.minipay.payment.domain.model.FundingOrderPage;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.BankCardRow;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.RechargeRow;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RechargeService {
    private final PaymentRepository repository;
    private final BankGateway bank;
    private final WalletInternalClient wallet;
    private final IdentityInternalClient identity;
    private final DomainEventWriter events;

    public RechargeService(
            PaymentRepository repository,
            BankGateway bank,
            WalletInternalClient wallet,
            IdentityInternalClient identity,
            DomainEventWriter events) {
        this.repository = repository;
        this.bank = bank;
        this.wallet = wallet;
        this.identity = identity;
        this.events = events;
    }

    public RechargeOrder create(
            UUID userId,
            String idempotencyKey,
            UUID bankCardId,
            long amountCent) {
        validateAmount(amountCent);
        String scopedKey = RequestSupport.scopedIdempotencyKey(userId, idempotencyKey);
        byte[] requestHash = RequestSupport.hash(
                "BANK_CARD:" + bankCardId + ":" + amountCent);
        RechargeRow existing = repository.findRechargeByIdempotency(scopedKey).orElse(null);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return resume(existing);
        }
        BankCardRow card = activeCard(userId, bankCardId);
        UUID rechargeId = UuidV7.generate();
        String rechargeNo = RequestSupport.businessNo("R", rechargeId);
        try {
            repository.insertRecharge(
                    rechargeId,
                    rechargeNo,
                    userId,
                    bankCardId,
                    scopedKey,
                    requestHash,
                    amountCent);
        } catch (DuplicateKeyException exception) {
            RechargeRow concurrent = repository.findRechargeByIdempotency(scopedKey)
                    .orElseThrow(() -> exception);
            requireSameRequest(concurrent.requestHash(), requestHash);
            return resume(concurrent);
        }
        return process(new RechargeRow(
                rechargeId,
                rechargeNo,
                userId,
                bankCardId,
                scopedKey,
                requestHash,
                amountCent,
                "BANK_CARD",
                "PROCESSING",
                null,
                java.time.Instant.now()), card);
    }

    public RechargeOrder get(UUID userId, UUID rechargeId) {
        return repository.findRecharge(userId, rechargeId)
                .orElseThrow(() -> new PaymentProblemException(
                        "RECHARGE_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public FundingOrderPage list(UUID userId, int page, int size) {
        return repository.listRecharges(userId, Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    /** Creates a payment-password-gated intent.  No bank debit occurs in this method. */
    public RechargeOrder prepare(
            UUID userId, String idempotencyKey, UUID bankCardId, long amountCent) {
        validateAmount(amountCent);
        String scopedKey = RequestSupport.scopedIdempotencyKey(userId, idempotencyKey);
        byte[] requestHash = RequestSupport.hash("BANK_CARD:" + bankCardId + ":" + amountCent);
        RechargeRow existing = repository.findRechargeByIdempotency(scopedKey).orElse(null);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return existing.toPublicModel();
        }
        activeCard(userId, bankCardId);
        UUID rechargeId = UuidV7.generate();
        String rechargeNo = RequestSupport.businessNo("R", rechargeId);
        try {
            return repository.insertRechargeIntent(rechargeId, rechargeNo, userId, bankCardId,
                    scopedKey, requestHash, amountCent);
        } catch (DuplicateKeyException exception) {
            RechargeRow concurrent = repository.findRechargeByIdempotency(scopedKey)
                    .orElseThrow(() -> exception);
            requireSameRequest(concurrent.requestHash(), requestHash);
            return concurrent.toPublicModel();
        }
    }

    public RechargeOrder confirm(
            UUID userId, UUID rechargeId, String paymentAuthToken, String deviceId) {
        RechargeRow order = repository.findRechargeRow(userId, rechargeId)
                .orElseThrow(() -> new PaymentProblemException("RECHARGE_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"PENDING_CONFIRMATION".equals(order.status())) {
            return order.toPublicModel();
        }
        UUID authorizationId = identity.verifyAndConsume(paymentAuthToken, userId,
                "RECHARGE_ORDER", rechargeId, order.amountCent(), deviceId);
        if (!repository.authorizeRecharge(rechargeId, authorizationId)) {
            repository.closeExpiredRechargeIntent(rechargeId);
            return get(userId, rechargeId);
        }
        return process(new RechargeRow(order.rechargeId(), order.rechargeNo(), order.userId(),
                order.bankCardId(), order.idempotencyKey(), order.requestHash(), order.amountCent(),
                order.channel(), "PROCESSING", order.failureCode(), order.updatedAt()),
                activeCard(order.userId(), order.bankCardId()));
    }

    private RechargeOrder resume(RechargeRow order) {
        if (!"PROCESSING".equals(order.status())) {
            return order.toPublicModel();
        }
        return process(order, activeCard(order.userId(), order.bankCardId()));
    }

    private RechargeOrder process(RechargeRow order, BankCardRow card) {
        BankResult debit = bank.debit(
                card.providerToken(),
                order.amountCent(),
                order.rechargeNo());
        if (!debit.succeeded()) {
            repository.completeRecharge(order.rechargeId(), "FAILED", debit.failureCode());
            return get(order.userId(), order.rechargeId());
        }
        UUID eventId = UuidV7.generate();
        try {
            wallet.creditRecharge(
                    eventId,
                    order.rechargeId(),
                    order.rechargeNo(),
                    order.userId(),
                    order.amountCent());
        } catch (RuntimeException exception) {
            BankResult refund = bank.refundDebit(
                    card.providerToken(),
                    order.amountCent(),
                    order.rechargeNo() + "-REFUND");
            if (refund.succeeded()) {
                repository.completeRecharge(
                        order.rechargeId(), "FAILED", "WALLET_POSTING_REJECTED");
            }
            throw exception;
        }
        events.writeAfter(
                () -> repository.completeRecharge(order.rechargeId(), "SUCCEEDED", null),
                "payment.recharge.succeeded",
                "RECHARGE_ORDER",
                order.rechargeId(),
                Map.of(
                        "rechargeId", order.rechargeId(),
                        "rechargeNo", order.rechargeNo(),
                        "userId", order.userId(),
                        "bankCardId", order.bankCardId(),
                        "amountCent", order.amountCent(),
                        "currency", "CNY",
                        "channel", "BANK_CARD"));
        return get(order.userId(), order.rechargeId());
    }

    private BankCardRow activeCard(UUID userId, UUID cardId) {
        BankCardRow card = repository.findBankCardRow(userId, cardId, false)
                .orElseThrow(() -> new PaymentProblemException(
                        "BANK_CARD_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"ACTIVE".equals(card.status())) {
            throw new PaymentProblemException("BANK_CARD_DISABLED", HttpStatus.CONFLICT);
        }
        return card;
    }

    private void validateAmount(long amountCent) {
        if (amountCent < 1 || amountCent > 1_000_000L) {
            throw new PaymentProblemException(
                    "AMOUNT_OUT_OF_RANGE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void requireSameRequest(byte[] stored, byte[] supplied) {
        if (!Arrays.equals(stored, supplied)) {
            throw new PaymentProblemException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
        }
    }

}
