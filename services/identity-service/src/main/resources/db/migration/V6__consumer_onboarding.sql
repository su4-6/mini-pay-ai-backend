ALTER TABLE user_profile
  ADD COLUMN avatar_object_key VARCHAR(512) NULL AFTER nickname,
  ADD COLUMN onboarding_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER status,
  ADD COLUMN onboarding_completed_at DATETIME(6) NULL AFTER onboarding_status,
  ADD CONSTRAINT chk_user_profile_onboarding_status
    CHECK (onboarding_status IN ('PENDING', 'COMPLETED'));

-- Accounts created before this migration are existing users and must not be
-- forced through the new first-login initialization flow.
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
