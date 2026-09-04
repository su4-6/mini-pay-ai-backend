package com.minipay.commerce.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.application.CommerceOrderService;
import com.minipay.commerce.application.YshopPaymentEventService;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentResultConsumer {
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "payment.food-order.succeeded",
            "payment.food-order.failed",
            "payment.food-order.closed",
            "payment.refund.processing",
            "payment.refund.succeeded",
            "payment.refund.failed");

    private final ObjectMapper objectMapper;
    private final CommerceOrderService orders;
    private final YshopPaymentEventService yshopOrders;

    public PaymentResultConsumer(
            ObjectMapper objectMapper,
            CommerceOrderService orders,
            YshopPaymentEventService yshopOrders) {
        this.objectMapper = objectMapper;
        this.orders = orders;
        this.yshopOrders = yshopOrders;
    }

    @RabbitListener(queues = CommerceMessagingConfiguration.PAYMENT_RESULTS_QUEUE)
    public void consume(String body) throws Exception {
        JsonNode envelope = objectMapper.readTree(body);
        String eventType = requiredText(envelope, "eventType");
        if (!SUPPORTED_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported payment event type");
        }
        int payloadVersion = envelope.path("payloadVersion").asInt(-1);
        if (payloadVersion != 1) {
            throw new IllegalArgumentException("Unsupported payment event payload version");
        }
        UUID eventId = UUID.fromString(requiredText(envelope, "eventId"));
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) throw new IllegalArgumentException("Payment event payload is required");
        JsonNode foodOrderId = payload.get("foodOrderId");
        if (foodOrderId == null && eventType.startsWith("payment.refund.")) {
            return;
        }
        UUID orderId = UUID.fromString(requiredText(payload, "foodOrderId"));
        if (yshopOrders.isExternalOrder(orderId)) {
            switch (eventType) {
                case "payment.food-order.succeeded" -> yshopOrders.paymentSucceeded(
                        eventId, orderId,
                        UUID.fromString(requiredText(payload, "paymentOrderId")),
                        requiredPositiveAmount(payload), requiredText(payload, "currency"));
                case "payment.food-order.failed" -> yshopOrders.paymentFailed(eventId, orderId);
                case "payment.food-order.closed" -> yshopOrders.paymentClosed(
                        eventId, orderId,
                        UUID.fromString(requiredText(payload, "paymentOrderId")),
                        optionalText(payload, "failureCode"));
                case "payment.refund.processing" -> yshopOrders.refundProcessing(eventId, orderId);
                case "payment.refund.succeeded" -> yshopOrders.refundResult(
                        eventId, orderId,
                        UUID.fromString(requiredText(payload, "paymentOrderId")),
                        optionalText(payload, "refundId"), "REFUNDED", requiredPositiveAmount(payload));
                case "payment.refund.failed" -> yshopOrders.refundResult(
                        eventId, orderId,
                        UUID.fromString(requiredText(payload, "paymentOrderId")),
                        optionalText(payload, "refundId"), "REJECTED", requiredPositiveAmount(payload));
                default -> throw new IllegalStateException("Event type validation drift");
            }
            return;
        }
        switch (eventType) {
            case "payment.food-order.succeeded" -> orders.paymentSucceeded(
                    eventId,
                    orderId,
                    UUID.fromString(requiredText(payload, "paymentOrderId")),
                    requiredPositiveAmount(payload));
            case "payment.food-order.failed" -> orders.paymentFailed(eventId, orderId);
            case "payment.food-order.closed" -> orders.paymentFailed(eventId, orderId);
            case "payment.refund.processing" -> orders.refundProcessing(eventId, orderId);
            case "payment.refund.succeeded" ->
                    orders.refundSucceeded(eventId, orderId, requiredPositiveAmount(payload));
            case "payment.refund.failed" -> orders.refundFailed(eventId, orderId);
            default -> throw new IllegalStateException("Event type validation drift");
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    private static long requiredPositiveAmount(JsonNode payload) {
        JsonNode value = payload.get("amountCent");
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Authoritative amountCent is required");
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.textValue();
    }
}
