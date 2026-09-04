package com.minipay.wallet.infrastructure.messaging;

import static com.minipay.wallet.infrastructure.persistence.WalletRepository.uuidToBytes;

import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletInboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public WalletInboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean start(UUID eventId, String eventType, String consumerName) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO inbox_message (
                      event_id, consumer_name, event_type, received_at
                    ) VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                    """, uuidToBytes(eventId), consumerName, eventType) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public void complete(UUID eventId, String consumerName) {
        int updated = jdbcTemplate.update("""
                UPDATE inbox_message
                SET processed_at = UTC_TIMESTAMP(6)
                WHERE event_id = ? AND consumer_name = ? AND processed_at IS NULL
                """, uuidToBytes(eventId), consumerName);
        if (updated != 1) {
            throw new IllegalStateException("Inbox completion did not converge");
        }
    }
}
