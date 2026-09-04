package com.minipay.commerce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FoodOrderTest {
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void followsPaymentFulfillmentStateMachine() {
        FoodOrder unpaid = order();
        UUID paymentOrderId = UUID.randomUUID();

        FoodOrder paid = unpaid.paymentSucceeded(paymentOrderId, 3300, NOW.plusSeconds(1));
        FoodOrder accepted = paid.merchantAccepted(NOW.plusSeconds(2));
        FoodOrder preparing = accepted.preparing(NOW.plusSeconds(3));
        FoodOrder delivering = preparing.delivering(NOW.plusSeconds(4));
        FoodOrder delivered = delivering.delivered(NOW.plusSeconds(5));

        assertThat(delivered.status()).isEqualTo(FoodOrderStatus.DELIVERED);
        assertThat(delivered.paymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(delivered.version()).isEqualTo(5);
    }

    @Test
    void requiresAuthoritativePaymentAndRefundAmounts() {
        FoodOrder unpaid = order();
        UUID paymentOrderId = UUID.randomUUID();

        assertThatThrownBy(() -> unpaid.paymentSucceeded(paymentOrderId, 3299, NOW))
                .isInstanceOf(CommerceDomainException.class)
                .extracting("code").isEqualTo("COMMERCE_PAYMENT_AMOUNT_MISMATCH");

        FoodOrder cancelling = unpaid.paymentSucceeded(paymentOrderId, 3300, NOW)
                .requestCancellation(NOW.plusSeconds(1));
        assertThatThrownBy(() -> cancelling.refundSucceeded(3299, NOW.plusSeconds(2)))
                .isInstanceOf(CommerceDomainException.class)
                .extracting("code").isEqualTo("COMMERCE_REFUND_AMOUNT_MISMATCH");
    }

    @Test
    void onlyAllowsCancellationBeforeMerchantAcceptance() {
        FoodOrder paid = order().paymentSucceeded(UUID.randomUUID(), 3300, NOW);
        assertThat(paid.requestCancellation(NOW.plusSeconds(1)).status())
                .isEqualTo(FoodOrderStatus.CANCELLATION_PENDING);

        FoodOrder accepted = paid.merchantAccepted(NOW.plusSeconds(1));
        assertThatThrownBy(() -> accepted.requestCancellation(NOW.plusSeconds(2)))
                .isInstanceOf(CommerceDomainException.class)
                .extracting("code").isEqualTo("COMMERCE_ORDER_NOT_CANCELLABLE");
    }

    @Test
    void makesIdenticalFinalEventsIdempotent() {
        UUID paymentOrderId = UUID.randomUUID();
        FoodOrder paid = order().paymentSucceeded(paymentOrderId, 3300, NOW);
        assertThat(paid.paymentSucceeded(paymentOrderId, 3300, NOW.plusSeconds(1))).isSameAs(paid);

        FoodOrder cancelled = paid.requestCancellation(NOW.plusSeconds(2))
                .refundProcessing(NOW.plusSeconds(3))
                .refundSucceeded(3300, NOW.plusSeconds(4));
        assertThat(cancelled.refundSucceeded(3300, NOW.plusSeconds(5))).isSameAs(cancelled);
    }

    private static FoodOrder order() {
        return new FoodOrder(
                UUID.randomUUID(), "FO202608080001", UUID.randomUUID(), UUID.randomUUID(),
                "Sandbox merchant", UUID.randomUUID(), "Home · *** Road", UUID.randomUUID(),
                null, 3000, 300, 0, 3300, FoodOrderStatus.PENDING_PAYMENT,
                PaymentStatus.UNPAID, RefundStatus.NONE, NOW.plusSeconds(900), 0, NOW, NOW);
    }
}
