package com.minipay.payment.application.service;

import com.minipay.payment.application.port.BankGateway;
import com.minipay.payment.application.port.BankGateway.BankResult;
import com.minipay.payment.domain.model.WithdrawalOrder;
import com.minipay.payment.domain.model.FundingOrderPage;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.client.WalletInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.BankCardRow;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.WithdrawalRow;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WithdrawalService {
    private final PaymentRepository repository;
    private final BankGateway bank;
    private final WalletInternalClient wallet;
    private final IdentityInternalClient identity;
    private final DomainEventWriter events;

    public WithdrawalService(
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

    public WithdrawalOrder create(
            UUID userId,
            String idempotencyKey,
            UUID bankCardId,
            long amountCent) {
        validateAmount(amountCent);
        String scopedKey = RequestSupport.scopedIdempotencyKey(userId, idempotencyKey);
        byte[] requestHash = RequestSupport.hash(
                "BANK_CARD:" + bankCardId + ":" + amountCent);
        WithdrawalRow existing = repository.findWithdrawalByIdempotency(scopedKey)
                .orElse(null);
        if (existing != null) {
            requireSameRequest(existing.requestHash(), requestHash);
            return existing.toPublicModel();
        }
        activeCard(userId, bankCardId);
        UUID withdrawalId = UuidV7.generate();
        String withdrawalNo = RequestSupport.businessNo("W", withdrawalId);
        try {
            repository.insertWithdrawal(
                    withdrawalId,
                    withdrawalNo,
                    userId,
                    bankCardId,
                    scopedKey,
                    requestHash,
                    amountCent);
        } catch (DuplicateKeyException exception) {
            WithdrawalRow concurrent = repository.findWithdrawalByIdempotency(scopedKey)
                    .orElseThrow(() -> exception);
            requireSameRequest(concurrent.requestHash(), requestHash);
            return concurrent.toPublicModel();
        }
        return get(userId, withdrawalId);
    }

    public WithdrawalOrder get(UUID userId, UUID withdrawalId) {
        return repository.findWithdrawal(userId, withdrawalId)
                .orElseThrow(() -> new PaymentProblemException(
                        "WITHDRAWAL_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public FundingOrderPage list(UUID userId, int page, int size) {
        return repository.listWithdrawals(userId, Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    public WithdrawalOrder confirm(
            UUID userId,
            UUID withdrawalId,
            String paymentAuthToken,
            String deviceId) {
        WithdrawalRow order = repository.findWithdrawalByIdempotencyForOrder(
                        userId, withdrawalId)
                .orElseThrow(() -> new PaymentProblemException(
                        "WITHDRAWAL_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"PROCESSING".equals(order.status())) {
            return order.toPublicModel();
        }
        if (order.authorizationId() == null) {
            UUID authorizationId = identity.verifyAndConsume(
                    paymentAuthToken,
                    userId,
                    "WITHDRAWAL_ORDER",
                    withdrawalId,
                    order.amountCent(),
                    deviceId);
            repository.authorizeWithdrawal(withdrawalId, authorizationId);
        }
        return process(order);
    }

    private WithdrawalOrder process(WithdrawalRow order) {
        BankCardRow card = activeCard(order.userId(), order.bankCardId());
        wallet.debitWithdrawal(
                order.withdrawalId(),
                order.withdrawalNo(),
                order.userId(),
                order.amountCent());
        BankResult payout = bank.payout(
                card.providerToken(),
                order.amountCent(),
                order.withdrawalNo());
        if (!payout.succeeded()) {
            String failureCode = payout.failureCode() == null
                    ? "BANK_PAYOUT_FAILED" : payout.failureCode();
            wallet.reverseWithdrawal(
                    order.withdrawalId(),
                    order.withdrawalNo(),
                    order.userId(),
                    order.amountCent(),
                    failureCode);
            repository.completeWithdrawal(
                    order.withdrawalId(), "FAILED", null, failureCode);
            return get(order.userId(), order.withdrawalId());
        }
        wallet.completeWithdrawal(
                order.withdrawalId(),
                order.withdrawalNo(),
                order.userId());
        events.writeAfter(
                () -> repository.completeWithdrawal(
                        order.withdrawalId(),
                        "SUCCEEDED",
                        payout.transactionNo(),
                        null),
                "payment.withdrawal.succeeded",
                "WITHDRAWAL_ORDER",
                order.withdrawalId(),
                Map.of(
                        "withdrawalId", order.withdrawalId(),
                        "withdrawalNo", order.withdrawalNo(),
                        "userId", order.userId(),
                        "bankCardId", order.bankCardId(),
                        "amountCent", order.amountCent(),
                        "currency", "CNY"));
        return get(order.userId(), order.withdrawalId());
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
