ALTER TABLE payment_authorization
  ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER user_id,
  ADD COLUMN request_hash BINARY(32) NULL AFTER idempotency_key,
  ADD UNIQUE KEY uk_payment_authorization_user_idempotency (user_id, idempotency_key);
