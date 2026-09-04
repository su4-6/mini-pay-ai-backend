package com.minipay.payment.infrastructure.messaging;

import com.minipay.payment.infrastructure.messaging.MerchantNotificationDispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MerchantNotificationDispatcherTest {

    @Test
    void paymentSignatureUsesTheContractualFieldOrder() {
        assertEquals("PAYMENT\napp\nMO\nPO\n1000\n1754380800000\nnonce",
                MerchantNotificationDispatcher.signingSource(
                        "app", "MO", null, "PO", "payment.succeeded", 1000,
                        "1754380800000", "nonce"));
    }

    @Test
    void refundSignatureIncludesRefundBusinessNumberBeforeOriginalBusinessNumber() {
        assertEquals("REFUND\napp\nMO\nRF\nPO\n1000\n1754381100000\nnonce",
                MerchantNotificationDispatcher.signingSource(
                        "app", "MO", "RF", "PO", "refund.succeeded", 1000,
                        "1754381100000", "nonce"));
    }

    @Test
    void legacyNotificationWithoutOccurredAtFallsBackToCreatedAt() {
        Timestamp createdAt = Timestamp.from(Instant.parse("2026-08-10T19:19:40Z"));

        assertEquals(createdAt.toInstant(),
                MerchantNotificationDispatcher.notificationOccurredAt(null, createdAt));
    }
}
