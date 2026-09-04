package com.minipay.payment.application.service;

import com.minipay.payment.application.port.BankGateway;
import com.minipay.payment.application.port.BankGateway.TokenizedCard;
import com.minipay.payment.domain.model.BankBalance;
import com.minipay.payment.domain.model.BankCard;
import com.minipay.payment.domain.model.BankPaymentLimits;
import com.minipay.payment.domain.model.BankTransactionPage;
import com.minipay.payment.infrastructure.client.IdentityInternalClient;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.BankCardRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankCardService {
    private final BankGateway bank;
    private final PaymentRepository repository;
    private final IdentityInternalClient identity;

    public BankCardService(
            BankGateway bank,
            PaymentRepository repository,
            IdentityInternalClient identity) {
        this.bank = bank;
        this.repository = repository;
        this.identity = identity;
    }

    @Transactional
    public BankCard bind(
            UUID userId,
            String holderName,
            String cardNumber,
            String verificationCode) {
        TokenizedCard tokenized = bank.bind(
                userId, holderName, cardNumber, verificationCode);
        BankCard existing = repository.findBankCardByProviderToken(
                userId, tokenized.providerToken()).orElse(null);
        if (existing != null) {
            return existing;
        }
        return repository.insertBankCard(
                UuidV7.generate(),
                userId,
                tokenized.provider(),
                tokenized.providerToken(),
                tokenized.bankName(),
                tokenized.cardType(),
                tokenized.maskedCardNo(),
                tokenized.lastFour(),
                holderName.strip());
    }

    @Transactional(readOnly = true)
    public List<BankCard> list(UUID userId) {
        return repository.listBankCards(userId);
    }

    @Transactional(readOnly = true)
    public BankCard get(UUID userId, UUID cardId) {
        return card(userId, cardId, false).toPublicModel();
    }

    public BankBalance balance(
            UUID userId,
            UUID cardId,
            String paymentAuthToken,
            String deviceId) {
        BankCardRow card = card(userId, cardId, true);
        identity.verifyAndConsume(
                paymentAuthToken,
                userId,
                "BANK_CARD_BALANCE_QUERY",
                cardId,
                0L,
                deviceId);
        return bank.balance(card.providerToken()).forCard(cardId);
    }

    public BankPaymentLimits paymentLimits(UUID userId, UUID cardId) {
        BankCardRow card = card(userId, cardId, true);
        return bank.paymentLimits(card.providerToken()).forCard(cardId);
    }

    public BankTransactionPage transactions(
            UUID userId,
            UUID cardId,
            Instant from,
            Instant to,
            int page,
            int size) {
        if (page < 1 || size < 1 || size > 100 || (from != null && to != null && !from.isBefore(to))) {
            throw new PaymentProblemException("INVALID_PAGE_QUERY", HttpStatus.BAD_REQUEST);
        }
        BankCardRow card = card(userId, cardId, true);
        return bank.transactions(card.providerToken(), from, to, page, size);
    }

    @Transactional
    public void disable(UUID userId, UUID cardId) {
        if (repository.findBankCardRow(userId, cardId, true).isEmpty()) {
            throw new PaymentProblemException("BANK_CARD_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        repository.disableBankCard(userId, cardId);
    }

    private BankCardRow card(UUID userId, UUID cardId, boolean requireActive) {
        BankCardRow card = repository.findBankCardRow(userId, cardId, false)
                .orElseThrow(() -> new PaymentProblemException(
                        "BANK_CARD_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (requireActive && !"ACTIVE".equals(card.status())) {
            throw new PaymentProblemException("BANK_CARD_INACTIVE", HttpStatus.CONFLICT);
        }
        return card;
    }
}
