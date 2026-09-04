package com.minipay.payment.infrastructure.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOperationalMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentOperationalMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong pendingTransfers = new AtomicLong();
    private final AtomicLong staleTransfers = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong deadOutbox = new AtomicLong();

    public PaymentOperationalMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("minipay_transfer_processing", pendingTransfers, AtomicLong::get)
                .register(meters);
        Gauge.builder("minipay_transfer_processing_stale", staleTransfers, AtomicLong::get)
                .description("Transfers unresolved for more than five minutes")
                .register(meters);
        Gauge.builder("minipay_outbox_backlog", pendingOutbox, AtomicLong::get)
                .tag("service", "payment")
                .register(meters);
        Gauge.builder("minipay_outbox_dead", deadOutbox, AtomicLong::get)
                .tag("service", "payment")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${minipay.metrics.refresh-delay-ms:10000}")
    public void refresh() {
        pendingTransfers.set(count("""
                SELECT COUNT(*) FROM transfer_order WHERE status = 'PROCESSING'
                """));
        long stale = count("""
                SELECT COUNT(*) FROM transfer_order
                WHERE status = 'PROCESSING'
                  AND authorization_id IS NOT NULL
                  AND created_at < DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE)
                """);
        if (stale > 0 && staleTransfers.getAndSet(stale) != stale) {
            LOG.error("High-priority alert: {} transfer orders remain unresolved", stale);
        } else {
            staleTransfers.set(stale);
        }
        pendingOutbox.set(count("""
                SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING', 'PUBLISHING')
                """));
        deadOutbox.set(count("""
                SELECT COUNT(*) FROM outbox_event WHERE status = 'DEAD'
                """));
    }

    private long count(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
