package com.minipay.commerce.application;

import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.bytesToUuid;
import static com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository.uuidToBytes;
import static com.minipay.commerce.infrastructure.yshop.YshopModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.infrastructure.yshop.YshopGateway;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class YshopPaymentEventService {
    private static final String CONSUMER = "commerce-yshop-payment-result-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final YshopGateway yshop;

    public YshopPaymentEventService(JdbcTemplate jdbc, ObjectMapper json, YshopGateway yshop) {
        this.jdbc = jdbc;
        this.json = json;
        this.yshop = yshop;
    }

    public boolean isExternalOrder(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM food_external_order_ref WHERE order_ref_id = ? AND provider = 'YSHOP'",
                Integer.class, uuidToBytes(orderId));
        return count != null && count > 0;
    }

    @Transactional
    public void paymentSucceeded(
            UUID eventId, UUID orderId, UUID paymentOrderId, long amountCent, String currency) {
        ExternalOrder order = lockOrder(orderId);
        if (!claim(eventId, "payment.food-order.succeeded")) return;
        if (order.amountCent() != amountCent || !order.currency().equals(currency)) {
            throw problem("COMMERCE_PAYMENT_AMOUNT_MISMATCH", "支付金额与外卖订单不一致");
        }
        try {
            int changed = jdbc.update("""
                    UPDATE food_external_order_ref
                       SET payment_order_id = ?, payment_status = 'PAID', updated_at = UTC_TIMESTAMP(6)
                     WHERE order_ref_id = ? AND payment_status IN ('UNPAID', 'PROCESSING', 'FAILED', 'PAID')
                       AND (payment_order_id IS NULL OR payment_order_id = ?)
                    """, uuidToBytes(paymentOrderId), uuidToBytes(orderId), uuidToBytes(paymentOrderId));
            if (changed != 1) throw problem("COMMERCE_PAYMENT_REFERENCE_CONFLICT", "支付单已绑定其他订单");
        } catch (DuplicateKeyException exception) {
            throw problem("COMMERCE_PAYMENT_REFERENCE_CONFLICT", "支付单已绑定其他订单");
        }
        enqueue(eventId, orderId, "payment.food-order.succeeded", Map.of(
                "eventId", eventId, "subject", order.userId(), "paymentOrderId", paymentOrderId,
                "amountCent", amountCent, "currency", currency, "status", "PAID"));
        complete(eventId);
    }

    @Transactional
    public void paymentFailed(UUID eventId, UUID orderId) {
        lockOrder(orderId);
        if (!claim(eventId, "payment.food-order.failed")) return;
        jdbc.update("""
                UPDATE food_external_order_ref SET payment_status = 'FAILED', updated_at = UTC_TIMESTAMP(6)
                 WHERE order_ref_id = ? AND payment_status <> 'PAID'
                """, uuidToBytes(orderId));
        complete(eventId);
    }

    @Transactional
    public void paymentClosed(UUID eventId, UUID orderId, UUID paymentOrderId, String reason) {
        ExternalOrder order = lockOrder(orderId);
        if (!claim(eventId, "payment.food-order.closed")) return;
        if ("PAID".equals(order.paymentStatus())) {
            throw problem("COMMERCE_PAID_ORDER_CANNOT_CLOSE", "已支付订单不能关闭");
        }
        jdbc.update("""
                UPDATE food_external_order_ref
                   SET payment_order_id = COALESCE(payment_order_id, ?), payment_status = 'CLOSED',
                       fulfillment_status = 'CANCELLED', updated_at = UTC_TIMESTAMP(6)
                 WHERE order_ref_id = ?
                """, uuidToBytes(paymentOrderId), uuidToBytes(orderId));
        enqueue(eventId, orderId, "payment.food-order.closed", Map.of(
                "eventId", eventId, "subject", order.userId(), "paymentOrderId", paymentOrderId,
                "reason", reason == null ? "PAYMENT_ORDER_EXPIRED" : reason));
        complete(eventId);
    }

    @Transactional
    public void refundProcessing(UUID eventId, UUID orderId) {
        lockOrder(orderId);
        if (!claim(eventId, "payment.refund.processing")) return;
        jdbc.update("UPDATE food_external_order_ref SET refund_status = 'PROCESSING', updated_at = UTC_TIMESTAMP(6) WHERE order_ref_id = ?",
                uuidToBytes(orderId));
        complete(eventId);
    }

    @Transactional
    public void refundResult(
            UUID eventId, UUID orderId, UUID paymentOrderId, String refundRequestId,
            String status, long amountCent) {
        ExternalOrder order = lockOrder(orderId);
        String eventType = "REFUNDED".equals(status)
                ? "payment.refund.succeeded" : "payment.refund.failed";
        if (!claim(eventId, eventType)) return;
        if (order.amountCent() != amountCent || order.paymentOrderId() == null
                || !order.paymentOrderId().equals(paymentOrderId)) {
            throw problem("COMMERCE_REFUND_REFERENCE_MISMATCH", "退款与原支付单不一致");
        }
        jdbc.update("UPDATE food_external_order_ref SET refund_status = ?, updated_at = UTC_TIMESTAMP(6) WHERE order_ref_id = ?",
                status, uuidToBytes(orderId));
        enqueue(eventId, orderId, eventType, Map.of(
                "eventId", eventId, "subject", order.userId(), "paymentOrderId", paymentOrderId,
                "refundRequestId", refundRequestId == null ? eventId.toString() : refundRequestId,
                "status", status));
        complete(eventId);
    }

    @Transactional
    public RefundSubmission submitRefund(UUID orderId, UUID refundRequestId, String reason) {
        ExternalOrder order = lockOrder(orderId);
        String normalizedReason = reason == null || reason.isBlank()
                ? "yshop后台审核通过" : reason.strip();
        if (!"PAID".equals(order.paymentStatus()) || order.paymentOrderId() == null) {
            throw problem("COMMERCE_ORDER_NOT_REFUNDABLE", "外卖订单尚未支付");
        }
        if ("REFUNDED".equals(order.refundStatus())) {
            return new RefundSubmission(refundRequestId, orderId, "REFUNDED");
        }
        try {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO outbox_event
                      (event_id, event_type, aggregate_type, aggregate_id, occurred_at, trace_id,
                       payload_version, payload, status, attempts, next_attempt_at,
                       lease_owner, lease_until, last_error, published_at, created_at)
                    VALUES (?, 'commerce.refund.requested', 'FOOD_ORDER', ?, ?, ?, 1, ?,
                            'PENDING', 0, ?, NULL, NULL, NULL, NULL, ?)
                    """, uuidToBytes(refundRequestId), uuidToBytes(orderId), Timestamp.from(now),
                    refundRequestId.toString(), writeJson(Map.of(
                            "orderId", orderId, "paymentOrderId", order.paymentOrderId(),
                            "amountCent", order.amountCent(), "reason", normalizedReason)),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // The yshop outbox may safely retry the same approved refund request.
        }
        jdbc.update("UPDATE food_external_order_ref SET refund_status = 'PROCESSING', updated_at = UTC_TIMESTAMP(6) WHERE order_ref_id = ?",
                uuidToBytes(orderId));
        return new RefundSubmission(refundRequestId, orderId, "PROCESSING");
    }

    @Transactional
    public RefundSubmission submitRefundByExternalOrderNo(
            String externalOrderNo, UUID refundRequestId, String reason) {
        List<UUID> orderIds = jdbc.query("""
                SELECT order_ref_id
                  FROM food_external_order_ref
                 WHERE provider = 'YSHOP' AND external_order_no = ?
                """, (rs, ignored) -> bytesToUuid(rs.getBytes("order_ref_id")), externalOrderNo);
        if (orderIds.isEmpty()) {
            throw problem("COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在");
        }
        return submitRefund(orderIds.get(0), refundRequestId, reason);
    }

    @Scheduled(fixedDelayString = "${minipay.commerce.provider-delivery-delay:1000ms}")
    public void deliverCallbacks() {
        List<Delivery> rows = jdbc.query("""
                SELECT d.delivery_id, d.event_id, d.order_ref_id, d.event_type, d.payload,
                       o.external_order_no
                  FROM food_provider_delivery d
                  JOIN food_external_order_ref o ON o.order_ref_id = d.order_ref_id
                 WHERE d.status = 'PENDING' AND d.next_attempt_at <= UTC_TIMESTAMP(6)
                 ORDER BY d.created_at LIMIT 50
                """, (rs, ignored) -> new Delivery(bytesToUuid(rs.getBytes("delivery_id")),
                bytesToUuid(rs.getBytes("event_id")), bytesToUuid(rs.getBytes("order_ref_id")),
                rs.getString("event_type"), rs.getString("payload"), rs.getString("external_order_no")));
        rows.forEach(this::deliver);
    }

    private void deliver(Delivery delivery) {
        int claimed = jdbc.update("""
                UPDATE food_provider_delivery SET status = 'SENDING', attempts = attempts + 1,
                       updated_at = UTC_TIMESTAMP(6)
                 WHERE delivery_id = ? AND status = 'PENDING'
                """, uuidToBytes(delivery.id()));
        if (claimed != 1) return;
        try {
            JsonNode payload = json.readTree(delivery.payload());
            switch (delivery.eventType()) {
                case "payment.food-order.succeeded" -> yshop.paymentSucceeded(
                        delivery.externalOrderNo(), json.treeToValue(payload, PaymentResult.class));
                case "payment.food-order.closed" -> yshop.paymentClosed(
                        delivery.externalOrderNo(), json.treeToValue(payload, CloseResult.class));
                case "payment.refund.succeeded", "payment.refund.failed" -> yshop.refundResult(
                        delivery.externalOrderNo(), json.treeToValue(payload, RefundResult.class));
                default -> throw new IllegalStateException("Unsupported yshop callback");
            }
            jdbc.update("UPDATE food_provider_delivery SET status = 'DELIVERED', updated_at = UTC_TIMESTAMP(6) WHERE delivery_id = ?",
                    uuidToBytes(delivery.id()));
        } catch (Exception exception) {
            jdbc.update("""
                    UPDATE food_provider_delivery
                       SET status = CASE WHEN attempts >= 10 THEN 'DEAD' ELSE 'PENDING' END,
                           next_attempt_at = DATE_ADD(UTC_TIMESTAMP(6),
                             INTERVAL LEAST(300, POW(2, LEAST(attempts, 8))) SECOND),
                           last_error_code = ?, updated_at = UTC_TIMESTAMP(6)
                     WHERE delivery_id = ?
                    """, exception.getClass().getSimpleName(), uuidToBytes(delivery.id()));
        }
    }

    private ExternalOrder lockOrder(UUID orderId) {
        List<ExternalOrder> rows = jdbc.query("""
                SELECT user_id, amount_cent, currency, payment_order_id, payment_status, refund_status
                  FROM food_external_order_ref WHERE order_ref_id = ? AND provider = 'YSHOP' FOR UPDATE
                """, (rs, ignored) -> new ExternalOrder(bytesToUuid(rs.getBytes("user_id")),
                rs.getLong("amount_cent"), rs.getString("currency"),
                rs.getBytes("payment_order_id") == null ? null : bytesToUuid(rs.getBytes("payment_order_id")),
                rs.getString("payment_status"), rs.getString("refund_status")), uuidToBytes(orderId));
        if (rows.isEmpty()) throw problem("COMMERCE_ORDER_NOT_FOUND", "外卖订单不存在");
        return rows.get(0);
    }

    private boolean claim(UUID eventId, String type) {
        try {
            jdbc.update("""
                    INSERT INTO inbox_message(event_id, consumer_name, event_type, received_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                    """, uuidToBytes(eventId), CONSUMER, type);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private void complete(UUID eventId) {
        jdbc.update("UPDATE inbox_message SET processed_at = UTC_TIMESTAMP(6) WHERE event_id = ? AND consumer_name = ?",
                uuidToBytes(eventId), CONSUMER);
    }

    private void enqueue(UUID eventId, UUID orderId, String type, Object payload) {
        jdbc.update("""
                INSERT INTO food_provider_delivery
                  (delivery_id, event_id, order_ref_id, event_type, payload, status,
                   attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, uuidToBytes(UuidV7.generate()), uuidToBytes(eventId), uuidToBytes(orderId),
                type, writeJson(payload));
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static CommerceApplicationException problem(String code, String message) {
        return new CommerceApplicationException(code, message);
    }

    public record RefundSubmission(UUID refundRequestId, UUID orderRefId, String status) { }
    private record ExternalOrder(UUID userId, long amountCent, String currency,
                                 UUID paymentOrderId, String paymentStatus, String refundStatus) { }
    private record Delivery(UUID id, UUID eventId, UUID orderId, String eventType,
                            String payload, String externalOrderNo) { }
}
