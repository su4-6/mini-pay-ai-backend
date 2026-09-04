CREATE TABLE account_security_operation (
  operation_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  operation_type VARCHAR(48) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (operation_id),
  UNIQUE KEY uk_account_security_operation_idempotency
    (user_id, operation_type, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_password_change_verification (
  verification_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  challenge_id VARCHAR(64) NOT NULL,
  issue_idempotency_key VARCHAR(128) NOT NULL,
  issue_request_hash BINARY(32) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  PRIMARY KEY (verification_id),
  UNIQUE KEY uk_payment_password_change_challenge (challenge_id),
  UNIQUE KEY uk_payment_password_change_issue
    (user_id, issue_idempotency_key),
  KEY idx_payment_password_change_expiry (expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
