package com.minipay.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.service.UuidV7;
import com.minipay.payment.infrastructure.persistence.MerchantRepository;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Independent idempotent projections for merchant metrics and callbacks. */
@Component
public class MerchantEventConsumer {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PAYMENT_SUCCEEDED = "payment.order.succeeded";
    private static final String REFUND_SUCCEEDED = "payment.refund.succeeded";
    private static final String PAYMENT_FAILED = "payment.order.failed";

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public MerchantEventConsumer(ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = MerchantEventConfiguration.METRIC_QUEUE)
    @Transactional
    public void metric(String message) {
        consume(message, "merchant_metric_inbox", true);
    }

    @RabbitListener(queues = MerchantEventConfiguration.NOTIFICATION_QUEUE)
    @Transactional
    public void notification(String message) {
        consume(message, "merchant_notification_inbox", false);
    }

    private void consume(String message, String inboxTable, boolean metricProjection) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            UUID eventId = UUID.fromString(required(envelope, "eventId"));
            String eventType = required(envelope, "eventType");
            if (!PAYMENT_SUCCEEDED.equals(eventType)
                    && !REFUND_SUCCEEDED.equals(eventType)
                    && !PAYMENT_FAILED.equals(eventType)) {
                return;
            }
            JsonNode payload = envelope.path("payload");
            if (!payload.hasNonNull("merchantId")) {
                // Non-merchant (P0/consumer) payment/refund: no merchant metric or
                // notification; skip instead of failing on the required field.
                return;
            }
            UUID merchantId = UUID.fromString(required(payload, "merchantId"));
            String appId = required(payload, "appId");
            long amountCent = payload.path("amountCent").asLong(-1);
            if (amountCent < 0) {
                throw new IllegalArgumentException("amountCent is required");
            }
            int inserted = jdbc.update("INSERT IGNORE INTO " + inboxTable
                            + " (event_id, event_type, payload_digest, processed_at)"
                            + " VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
                    uuid(eventId), eventType, MerchantRepository.hash(message));
            if (inserted == 0) return;

            Instant occurredAt = Instant.parse(required(envelope, "occurredAt"));
            if (metricProjection) {
                updateMetric(eventType, merchantId, amountCent, occurredAt);
            } else {
                createNotification(eventId, eventType, merchantId, appId, payload, occurredAt);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid merchant event", exception);
        }
    }

    private void updateMetric(
            String eventType, UUID merchantId, long amountCent, Instant occurredAt) {
        if (PAYMENT_FAILED.equals(eventType)) {
            return; // failed payments are not merchant revenue metrics
        }
        LocalDate date = occurredAt.atZone(SHANGHAI).toLocalDate();
        long paymentAmount = PAYMENT_SUCCEEDED.equals(eventType) ? amountCent : 0;
        long paymentCount = PAYMENT_SUCCEEDED.equals(eventType) ? 1 : 0;
        long refundAmount = REFUND_SUCCEEDED.equals(eventType) ? amountCent : 0;
        long refundCount = REFUND_SUCCEEDED.equals(eventType) ? 1 : 0;
        jdbc.update("""
                INSERT INTO merchant_daily_metric (
                  metric_date, merchant_id, successful_payment_count,
                  payment_amount_cent, successful_refund_count,
                  refund_amount_cent, calculated_at
                ) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                  successful_payment_count = successful_payment_count
                    + VALUES(successful_payment_count),
                  payment_amount_cent = payment_amount_cent + VALUES(payment_amount_cent),
                  successful_refund_count = successful_refund_count
                    + VALUES(successful_refund_count),
                  refund_amount_cent = refund_amount_cent + VALUES(refund_amount_cent),
                  calculated_at = UTC_TIMESTAMP(6)
                """, date, uuid(merchantId), paymentCount, paymentAmount,
                refundCount, refundAmount);
    }

    private void createNotification(
            UUID eventId,
            String eventType,
            UUID merchantId,
            String appId,
            JsonNode payload,
            Instant occurredAt) {
        String merchantOrderNo = required(payload, "merchantOrderNo");
        String paymentOrderNo = required(payload, "paymentOrderNo");
        String refundNo = REFUND_SUCCEEDED.equals(eventType)
                ? required(payload, "refundNo") : null;
        long amountCent = payload.path("amountCent").asLong(-1);
        jdbc.update("""
                INSERT IGNORE INTO merchant_notification (
                  notification_id, merchant_id, application_id, event_id,
                  type, status, next_attempt_at, attempts, event_type,
                  merchant_order_no, business_no, refund_business_no,
                  amount_cent, occurred_at, created_at, updated_at
                )
                SELECT ?, m.merchant_id, a.application_id, ?, ?, 'PENDING',
                       UTC_TIMESTAMP(6), 0, ?, ?, ?, ?, ?, ?,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                  FROM merchant m
                  JOIN merchant_application a ON a.merchant_id = m.merchant_id
                 WHERE m.merchant_id = ? AND a.app_id = ? AND a.status = 'ACTIVE'
                """, uuid(UuidV7.generate()), uuid(eventId),
                REFUND_SUCCEEDED.equals(eventType) ? "REFUND" : "PAYMENT",
                eventType, merchantOrderNo, paymentOrderNo, refundNo,
                amountCent, occurredAt, uuid(merchantId), appId);
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
