package com.minipay.payment.infrastructure.channel;

import com.minipay.payment.application.port.BankGateway;
import com.minipay.payment.application.service.PaymentProblemException;
import com.minipay.payment.domain.model.BankBalance;
import com.minipay.payment.domain.model.BankPaymentLimits;
import com.minipay.payment.domain.model.BankTransactionPage;
import com.minipay.payment.infrastructure.persistence.SandboxBankRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SandboxBankGateway implements BankGateway {
    public static final String SANDBOX_NOTICE =
            "沙箱银行数据，仅用于演示，不代表真实银行卡资产。";

    private final byte[] tokenizationKey;
    private final SandboxBankRepository repository;
    private final long initialBalanceCent;
    private final long singlePaymentLimitCent;
    private final long dailyPaymentLimitCent;

    public SandboxBankGateway(
            @Value("${minipay.channels.bank.sandbox-tokenization-key}") String tokenizationKey,
            @Value("${minipay.channels.bank.initial-balance-cent:10000000}")
            long initialBalanceCent,
            @Value("${minipay.channels.bank.single-payment-limit-cent:1000000}")
            long singlePaymentLimitCent,
            @Value("${minipay.channels.bank.daily-payment-limit-cent:5000000}")
            long dailyPaymentLimitCent,
            SandboxBankRepository repository) {
        if (tokenizationKey == null || tokenizationKey.length() < 32) {
            throw new IllegalStateException(
                    "Bank sandbox tokenization key must contain at least 32 characters");
        }
        if (initialBalanceCent < 1
                || singlePaymentLimitCent < 1
                || dailyPaymentLimitCent < singlePaymentLimitCent) {
            throw new IllegalStateException("Invalid sandbox bank balance or limits");
        }
        this.tokenizationKey = tokenizationKey.getBytes(StandardCharsets.UTF_8);
        this.initialBalanceCent = initialBalanceCent;
        this.singlePaymentLimitCent = singlePaymentLimitCent;
        this.dailyPaymentLimitCent = dailyPaymentLimitCent;
        this.repository = repository;
    }

    @Override
    @Transactional
    public TokenizedCard bind(
            UUID userId,
            String holderName,
            String cardNumber,
            String verificationCode) {
        String normalized = cardNumber == null ? "" : cardNumber.replace(" ", "");
        // The local sandbox deliberately validates only the documented input format. A real
        // acquiring channel performs issuer and checksum validation during tokenization.
        if (!normalized.matches("[0-9]{16,19}")) {
            throw new PaymentProblemException("INVALID_BANK_CARD", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (holderName == null || holderName.isBlank() || holderName.length() > 64) {
            throw new PaymentProblemException(
                    "INVALID_CARD_HOLDER", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!"123456".equals(verificationCode)) {
            throw new PaymentProblemException(
                    "BANK_VERIFICATION_FAILED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String lastFour = normalized.substring(normalized.length() - 4);
        String providerToken = HexFormat.of().formatHex(
                hmac(userId + ":" + normalized + ":" + holderName.strip()));
        String bankName = switch (normalized.substring(0, 2)) {
            case "62" -> "银联借记卡";
            case "40", "41", "42", "43", "44", "45", "46", "47", "48", "49" ->
                    "Visa 借记卡";
            case "51", "52", "53", "54", "55" -> "Mastercard 借记卡";
            default -> "沙箱银行";
        };
        TokenizedCard card = new TokenizedCard(
                "SANDBOX_BANK",
                providerToken,
                bankName,
                "DEBIT",
                "**** **** **** " + lastFour,
                lastFour);
        repository.initialize(
                providerToken,
                initialBalanceCent,
                singlePaymentLimitCent,
                dailyPaymentLimitCent);
        return card;
    }

    @Override
    @Transactional
    public BankResult debit(String providerToken, long amountCent, String requestNo) {
        return result("DEBIT", "EXPENSE", "MiniPay 充值", providerToken, amountCent, requestNo);
    }

    @Override
    @Transactional
    public BankResult refundDebit(String providerToken, long amountCent, String requestNo) {
        return result("DEBIT_REFUND", "INCOME", "MiniPay 充值退款", providerToken, amountCent, requestNo);
    }

    @Override
    @Transactional
    public BankResult payout(String providerToken, long amountCent, String requestNo) {
        return result("PAYOUT", "INCOME", "MiniPay 提现入账", providerToken, amountCent, requestNo);
    }

    @Override
    @Transactional(readOnly = true)
    public BankBalance balance(String providerToken) {
        var account = repository.findAccount(providerToken)
                .orElseThrow(() -> new PaymentProblemException(
                        "BANK_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND));
        return new BankBalance(
                null,
                account.availableAmountCent(),
                account.currency(),
                account.updatedAt(),
                SANDBOX_NOTICE);
    }

    @Override
    @Transactional(readOnly = true)
    public BankPaymentLimits paymentLimits(String providerToken) {
        var account = repository.findAccount(providerToken)
                .orElseThrow(() -> new PaymentProblemException(
                        "BANK_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND));
        long used = repository.dailyExpenseCent(providerToken);
        return new BankPaymentLimits(
                null,
                account.singlePaymentLimitCent(),
                account.dailyPaymentLimitCent(),
                used,
                Math.max(0L, account.dailyPaymentLimitCent() - used),
                account.currency(),
                Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public BankTransactionPage transactions(
            String providerToken, Instant from, Instant to, int page, int size) {
        if (repository.findAccount(providerToken).isEmpty()) {
            throw new PaymentProblemException("BANK_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return repository.transactions(providerToken, from, to, page, size);
    }

    private BankResult result(
            String operation,
            String direction,
            String description,
            String providerToken,
            long amountCent,
            String requestNo) {
        if (providerToken == null || providerToken.isBlank()
                || requestNo == null || requestNo.isBlank()
                || requestNo.length() > 128
                || amountCent <= 0) {
            return new BankResult(false, null, "BANK_REQUEST_REJECTED");
        }
        var existing = repository.findByRequest(providerToken, requestNo).orElse(null);
        if (existing != null) {
            return new BankResult(
                    "SUCCEEDED".equals(existing.status()),
                    existing.transactionId().toString(),
                    existing.failureCode());
        }
        var account = repository.lockAccount(providerToken).orElse(null);
        if (account == null) {
            return new BankResult(false, null, "BANK_ACCOUNT_NOT_FOUND");
        }
        boolean expense = "EXPENSE".equals(direction);
        long dailyUsed = expense ? repository.dailyExpenseCent(providerToken) : 0L;
        String failure = null;
        if (expense && amountCent > account.singlePaymentLimitCent()) {
            failure = "BANK_SINGLE_LIMIT_EXCEEDED";
        } else if (expense && dailyUsed + amountCent > account.dailyPaymentLimitCent()) {
            failure = "BANK_DAILY_LIMIT_EXCEEDED";
        } else if (expense && account.availableAmountCent() < amountCent) {
            failure = "BANK_INSUFFICIENT_BALANCE";
        }
        if (failure != null) {
            var failed = repository.insertTransaction(
                    providerToken, requestNo, operation, direction,
                    description, amountCent, "FAILED", failure);
            return new BankResult(false, failed.transactionId().toString(), failure);
        }
        long updated = expense
                ? account.availableAmountCent() - amountCent
                : Math.addExact(account.availableAmountCent(), amountCent);
        repository.updateBalance(providerToken, updated);
        var completed = repository.insertTransaction(
                providerToken, requestNo, operation, direction,
                description, amountCent, "SUCCEEDED", null);
        return new BankResult(true, completed.transactionId().toString(), null);
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenizationKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

}
