package com.minipay.identity.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
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
public class OutboxPublisher {
    public static final String EXCHANGE = "minipay.events";
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final String publisherId = UUID.randomUUID().toString();

    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${minipay.messaging.outbox-delay-ms:500}")
    public void publishBatch() {
        for (OutboxRow row : claimBatch()) publish(row);
    }

    private List<OutboxRow> claimBatch() {
        return transactions.execute(status -> {
            List<OutboxRow> rows = jdbcTemplate.query("""
                            SELECT event_id, event_type, aggregate_type, aggregate_id,
                                   occurred_at, trace_id, payload_version, payload
                            FROM outbox_event
                            WHERE (status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
                               OR (status = 'PUBLISHING' AND lease_until < UTC_TIMESTAMP(6))
                            ORDER BY occurred_at
                            LIMIT 50
                            FOR UPDATE SKIP LOCKED
                            """,
                    (resultSet, rowNumber) -> new OutboxRow(
                            AdminAccountRepository.bytesToUuid(resultSet.getBytes("event_id")),
                            resultSet.getString("event_type"),
                            resultSet.getString("aggregate_type"),
                            AdminAccountRepository.bytesToUuid(resultSet.getBytes("aggregate_id")),
                            resultSet.getTimestamp("occurred_at").toInstant(),
                            resultSet.getString("trace_id"),
                            resultSet.getInt("payload_version"),
                            resultSet.getString("payload")));
            for (OutboxRow row : rows) {
                jdbcTemplate.update("""
                        UPDATE outbox_event
                        SET status = 'PUBLISHING', lease_owner = ?,
                            lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND)
                        WHERE event_id = ?
                        """, publisherId,
                        AdminAccountRepository.uuidToBytes(row.eventId()));
            }
            return rows;
        });
    }

    private void publish(OutboxRow row) {
        try {
            CorrelationData correlation = new CorrelationData(row.eventId().toString());
            rabbitTemplate.convertAndSend(
                    EXCHANGE,
                    row.eventType(),
                    envelope(row),
                    message -> {
                        message.getMessageProperties()
                                .setContentType(MessageProperties.CONTENT_TYPE_JSON);
                        message.getMessageProperties().setMessageId(row.eventId().toString());
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(confirm.getReason() == null
                        ? "RabbitMQ returned the event" : confirm.getReason());
            }
            jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
                        lease_owner = NULL, lease_until = NULL, last_error = NULL
                    WHERE event_id = ? AND status = 'PUBLISHING' AND lease_owner = ?
                    """, AdminAccountRepository.uuidToBytes(row.eventId()), publisherId);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            int updated = jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET attempts = attempts + 1,
                        status = CASE WHEN attempts + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                        next_attempt_at = DATE_ADD(UTC_TIMESTAMP(6),
                          INTERVAL LEAST(300, POW(2, LEAST(attempts + 1, 8))) SECOND),
                        lease_owner = NULL, lease_until = NULL, last_error = LEFT(?, 512)
                    WHERE event_id = ? AND status = 'PUBLISHING' AND lease_owner = ?
                    """, MAX_ATTEMPTS, exception.toString(),
                    AdminAccountRepository.uuidToBytes(row.eventId()), publisherId);
            if (updated == 1) {
                LOG.warn("Outbox event {} was not broker-confirmed", row.eventId(), exception);
            }
        }
    }

    private String envelope(OutboxRow row) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", row.eventId());
            envelope.put("eventType", row.eventType());
            envelope.put("aggregateType", row.aggregateType());
            envelope.put("aggregateId", row.aggregateId());
            envelope.put("occurredAt", row.occurredAt());
            envelope.put("traceId", row.traceId() == null
                    ? row.eventId().toString() : row.traceId());
            envelope.put("payloadVersion", row.payloadVersion());
            envelope.put("payload", objectMapper.readTree(row.payload()));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize outbox event", exception);
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
