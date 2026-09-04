package com.minipay.commerce.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.CommerceOrderService;
import com.minipay.commerce.application.YshopPaymentEventService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentResultConsumerTest {
    private final CommerceOrderService orders = mock(CommerceOrderService.class);
    private final PaymentResultConsumer consumer = new PaymentResultConsumer(
            new ObjectMapper(), orders, mock(YshopPaymentEventService.class));

    @Test
    void routesAuthoritativeFoodPaymentResult() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID foodOrderId = UUID.randomUUID();
        UUID paymentOrderId = UUID.randomUUID();

        consumer.consume("""
                {
                  "eventId":"%s",
                  "eventType":"payment.food-order.succeeded",
                  "payloadVersion":1,
                  "payload":{
                    "foodOrderId":"%s",
                    "paymentOrderId":"%s",
                    "amountCent":3300
                  }
                }
                """.formatted(eventId, foodOrderId, paymentOrderId));

        verify(orders).paymentSucceeded(eventId, foodOrderId, paymentOrderId, 3300);
    }

    @Test
    void rejectsUnknownEventAndNonPositiveAmount() {
        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"%s","eventType":"payment.transfer.succeeded",
                 "payloadVersion":1,"payload":{}}
                """.formatted(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported payment event type");

        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"%s","eventType":"payment.food-order.succeeded",
                 "payloadVersion":1,"payload":{"foodOrderId":"%s","paymentOrderId":"%s","amountCent":0}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authoritative amountCent is required");
    }
}
