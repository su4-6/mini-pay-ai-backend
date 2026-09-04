package com.minipay.commerce.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.commerce.infrastructure.persistence.JdbcCommerceRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CommerceOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(CommerceOutboxPublisher.class);
    private static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final String publisherId = UUID.randomUUID().toString();

    public CommerceOutboxPublisher(
            JdbcTemplate jdbc,
            RabbitTemplate rabbit,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${minipay.commerce.outbox-delay:500ms}")
    public void publishBatch() {
        for (OutboxRow row : claimBatch()) publish(row);
    }

    private List<OutboxRow> claimBatch() {
        List<OutboxRow> result = transactions.execute(status -> {
            List<OutboxRow> rows = jdbc.query("""
                            SELECT event_id, event_type, aggregate_type, aggregate_id,
                                   occurred_at, trace_id, payload_version, payload
                            FROM outbox_event
                            WHERE (status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
                               OR (status = 'PUBLISHING' AND lease_until < UTC_TIMESTAMP(6))
                            ORDER BY occurred_at
                            LIMIT 50
                            FOR UPDATE SKIP LOCKED
                            """,
                    (rs, rowNumber) -> new OutboxRow(
                            JdbcCommerceRepository.bytesToUuid(rs.getBytes("event_id")),
                            rs.getString("event_type"),
                            rs.getString("aggregate_type"),
                            JdbcCommerceRepository.bytesToUuid(rs.getBytes("aggregate_id")),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("trace_id"),
                            rs.getInt("payload_version"),
                            rs.getString("payload")));
            for (OutboxRow row : rows) {
                jdbc.update("""
                                UPDATE outbox_event
                                SET status = 'PUBLISHING', lease_owner = ?,
                                    lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND)
                                WHERE event_id = ?
                                """,
                        publisherId, JdbcCommerceRepository.uuidToBytes(row.eventId()));
            }
            return rows;
        });
        return result == null ? List.of() : result;
    }

    private void publish(OutboxRow row) {
        try {
            CorrelationData correlation = new CorrelationData(row.eventId().toString());
            rabbit.convertAndSend(
                    CommerceMessagingConfiguration.EVENTS_EXCHANGE,
                    row.eventType(),
                    envelope(row),
                    message -> {
                        message.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
                        message.getMessageProperties().setMessageId(row.eventId().toString());
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(confirm.getReason() == null
                        ? "RabbitMQ returned the event" : confirm.getReason());
            }
            jdbc.update("""
                            UPDATE outbox_event
                            SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
                                lease_owner = NULL, lease_until = NULL, last_error = NULL
                            WHERE event_id = ? AND status = 'PUBLISHING' AND lease_owner = ?
                            """,
                    JdbcCommerceRepository.uuidToBytes(row.eventId()), publisherId);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            int updated = jdbc.update("""
                            UPDATE outbox_event
                            SET attempts = attempts + 1,
                                status = CASE WHEN attempts + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                                next_attempt_at = DATE_ADD(UTC_TIMESTAMP(6),
                                  INTERVAL LEAST(300, POW(2, LEAST(attempts + 1, 8))) SECOND),
                                lease_owner = NULL, lease_until = NULL, last_error = LEFT(?, 512)
                            WHERE event_id = ? AND status = 'PUBLISHING' AND lease_owner = ?
                            """,
                    MAX_ATTEMPTS, exception.getClass().getSimpleName(),
                    JdbcCommerceRepository.uuidToBytes(row.eventId()), publisherId);
            if (updated == 1) {
                LOG.warn("Commerce outbox event {} was not broker-confirmed", row.eventId());
            }
        }
    }

    private String envelope(OutboxRow row) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("eventId", row.eventId());
            value.put("eventType", row.eventType());
            value.put("aggregateType", row.aggregateType());
            value.put("aggregateId", row.aggregateId());
            value.put("occurredAt", row.occurredAt());
            value.put("traceId", row.traceId() == null ? row.eventId().toString() : row.traceId());
            value.put("payloadVersion", row.payloadVersion());
            value.put("payload", objectMapper.readTree(row.payload()));
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize commerce outbox event", exception);
        }
    }

    private record OutboxRow(
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Instant occurredAt,
            String traceId,
            int payloadVersion,
            String payload) {
    }
}
