ALTER TABLE memory_item
    MODIFY consent_message_id BINARY(16) NULL,
    ADD COLUMN consent_source VARCHAR(24) NOT NULL DEFAULT 'CHAT_EXPLICIT' AFTER consent_message_id,
    ADD COLUMN manual_idempotency_hash BINARY(32) NULL AFTER consent_source,
    ADD UNIQUE KEY uk_memory_item_manual_idempotency (user_id, manual_idempotency_hash);
