package com.minipay.payment.application.port;

import com.minipay.payment.domain.model.BankBalance;
import com.minipay.payment.domain.model.BankPaymentLimits;
import com.minipay.payment.domain.model.BankTransactionPage;
import java.time.Instant;
import java.util.UUID;

public interface BankGateway {
    TokenizedCard bind(
            UUID userId,
            String holderName,
            String cardNumber,
            String verificationCode);

    BankResult debit(String providerToken, long amountCent, String requestNo);

    BankResult refundDebit(String providerToken, long amountCent, String requestNo);

    BankResult payout(String providerToken, long amountCent, String requestNo);

    BankBalance balance(String providerToken);

    BankPaymentLimits paymentLimits(String providerToken);

    BankTransactionPage transactions(
            String providerToken, Instant from, Instant to, int page, int size);

    record TokenizedCard(
            String provider,
            String providerToken,
            String bankName,
            String cardType,
            String maskedCardNo,
            String lastFour) {
    }

    record BankResult(boolean succeeded, String transactionNo, String failureCode) {
    }
}
