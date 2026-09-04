-- One-time additive compatibility upgrade for the existing local minipay_identity schema.
-- Source of truth: identity-service Flyway V6-V9.
-- This file does not replace any existing Flyway migration and contains no DROP, DELETE or TRUNCATE.
-- Execute once only, before launching "MiniPay Standard Backend" against the legacy standard database.

USE minipay_identity;

ALTER TABLE user_profile
  ADD COLUMN avatar_object_key VARCHAR(512) NULL AFTER nickname,
  ADD COLUMN onboarding_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER status,
  ADD COLUMN onboarding_completed_at DATETIME(6) NULL AFTER onboarding_status,
  ADD CONSTRAINT chk_user_profile_onboarding_status
    CHECK (onboarding_status IN ('PENDING', 'COMPLETED'));

-- Existing accounts predate consumer onboarding, so preserve their current usability.
UPDATE user_profile
SET onboarding_status = 'COMPLETED',
    onboarding_completed_at = COALESCE(onboarding_completed_at, UTC_TIMESTAMP(6));

CREATE TABLE consumer_onboarding_request (
  idempotency_key VARCHAR(128) NOT NULL,
  user_id BINARY(16) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (idempotency_key),
  UNIQUE KEY uk_consumer_onboarding_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE payment_authorization
  ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER user_id,
  ADD COLUMN request_hash BINARY(32) NULL AFTER idempotency_key,
  ADD UNIQUE KEY uk_payment_authorization_user_idempotency (user_id, idempotency_key);

ALTER TABLE user_profile
  ADD COLUMN minipay_no VARCHAR(32) NULL AFTER login_name;

UPDATE user_profile
SET minipay_no = CONCAT('MP', UPPER(LEFT(HEX(user_id), 20)))
WHERE minipay_no IS NULL;

ALTER TABLE user_profile
  MODIFY COLUMN minipay_no VARCHAR(32) NOT NULL,
  ADD UNIQUE KEY uk_user_profile_minipay_no (minipay_no);

CREATE TABLE avatar_upload (
  upload_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  expected_content_type VARCHAR(64) NOT NULL,
  expected_size BIGINT NOT NULL,
  expected_sha256 CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  cleanup_attempts INT NOT NULL DEFAULT 0,
  next_cleanup_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (upload_id),
  UNIQUE KEY uk_avatar_upload_object_key (object_key),
  KEY idx_avatar_upload_owner (user_id, created_at),
  KEY idx_avatar_upload_cleanup (status, next_cleanup_at),
  CONSTRAINT chk_avatar_upload_status CHECK (
    status IN ('PENDING', 'CONSUMED', 'REJECTED', 'EXPIRED', 'DELETE_PENDING', 'DELETED')
  ),
  CONSTRAINT chk_avatar_upload_size CHECK (expected_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE outbox_event
  ADD COLUMN lease_owner VARCHAR(64) NULL AFTER next_attempt_at,
  ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_owner,
  ADD COLUMN last_error VARCHAR(512) NULL AFTER lease_until,
  ADD KEY idx_outbox_lease (status, lease_until);
