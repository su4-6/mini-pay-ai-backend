ALTER TABLE transfer_intent
  ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER intent_no,
  ADD COLUMN request_hash BINARY(32) NULL AFTER idempotency_key,
  ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'FORM' AFTER remark,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
  ADD UNIQUE KEY uk_transfer_intent_idempotency (idempotency_key),
  ADD CONSTRAINT chk_transfer_intent_source
    CHECK (source IN ('FORM', 'AI', 'PERSONAL_COLLECTION_CODE'));
