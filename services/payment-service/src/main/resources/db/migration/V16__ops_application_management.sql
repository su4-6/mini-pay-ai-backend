-- Application optimistic locking and per-merchant name uniqueness.
ALTER TABLE merchant_application
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
  ADD UNIQUE KEY uk_merchant_application_merchant_name (merchant_id, name);
