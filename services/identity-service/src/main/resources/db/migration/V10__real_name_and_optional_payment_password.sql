CREATE TABLE real_name_verification (
  verification_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  legal_name_masked VARCHAR(64) NOT NULL,
  legal_name_hash BINARY(32) NOT NULL,
  id_number_masked VARCHAR(32) NOT NULL,
  id_number_hash BINARY(32) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_reference VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL,
  failure_code VARCHAR(64) NULL,
  verified_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (verification_id),
  UNIQUE KEY uk_real_name_user_idempotency (user_id, idempotency_key),
  KEY idx_real_name_user_status_created (user_id, status, created_at),
  CONSTRAINT chk_real_name_status
    CHECK (status IN ('PROCESSING', 'VERIFIED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Normalize legacy credentials without overwriting a canonical credential that already exists.
UPDATE IGNORE user_credential
SET credential_type = 'PAYMENT_PASSWORD'
WHERE credential_type = 'PAY_PASSWORD';
