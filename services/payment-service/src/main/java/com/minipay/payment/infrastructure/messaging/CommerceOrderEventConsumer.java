package com.minipay.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.service.FoodPaymentSagaService;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CommerceOrderEventConsumer {
    private final ObjectMapper objectMapper;
    private final FoodPaymentSagaService saga;

    public CommerceOrderEventConsumer(ObjectMapper objectMapper, FoodPaymentSagaService saga) {
        this.objectMapper = objectMapper;
        this.saga = saga;
    }

    @RabbitListener(queues = MessagingConfiguration.FOOD_ORDER_QUEUE)
    public void consume(String body) throws Exception {
        JsonNode envelope = objectMapper.readTree(body);
        String eventType = requiredText(envelope, "eventType");
        int payloadVersion = envelope.path("payloadVersion").asInt(-1);
        UUID eventId = UUID.fromString(requiredText(envelope, "eventId"));
        JsonNode payload = envelope.path("payload");
        switch (eventType) {
            case "commerce.food-order.created" -> {
                if (payloadVersion == 1) {
                    saga.orderCreated(
                            eventId,
                            UUID.fromString(requiredText(payload, "orderId")),
                            requiredText(payload, "orderNo"),
                            UUID.fromString(requiredText(payload, "userId")),
                            payload.path("amountCent").longValue(),
                            requiredText(payload, "currency"));
                } else if (payloadVersion == 2 && "YSHOP".equals(requiredText(payload, "provider"))) {
                    saga.yshopOrderCreated(
                            eventId,
                            UUID.fromString(requiredText(payload, "orderId")),
                            requiredText(payload, "externalOrderNo"),
                            UUID.fromString(requiredText(payload, "userId")),
                            payload.path("amountCent").longValue(),
                            requiredText(payload, "currency"));
                } else {
                    throw new IllegalArgumentException("Unsupported Commerce food-order event");
                }
            }
            case "commerce.refund.requested" -> saga.refundRequested(
                    eventId,
                    UUID.fromString(requiredText(payload, "orderId")),
                    UUID.fromString(requiredText(payload, "paymentOrderId")),
                    payload.path("amountCent").longValue());
            default -> throw new IllegalArgumentException("Unsupported Commerce event");
        }
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.textValue();
    }
}
