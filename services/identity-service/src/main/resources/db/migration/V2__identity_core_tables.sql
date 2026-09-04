CREATE TABLE user_profile (
  user_id BINARY(16) NOT NULL,
  login_name VARCHAR(64) NOT NULL,
  phone_hash BINARY(32) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_user_profile_login_name (login_name),
  UNIQUE KEY uk_user_profile_phone_hash (phone_hash),
  KEY idx_user_profile_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_credential (
  credential_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  credential_type VARCHAR(32) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (credential_id),
  UNIQUE KEY uk_user_credential_user_type (user_id, credential_type),
  KEY idx_user_credential_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_authorization (
  authorization_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  intent_id BINARY(16) NOT NULL,
  subject_type VARCHAR(32) NOT NULL,
  subject_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (authorization_id),
  UNIQUE KEY uk_payment_authorization_token_hash (token_hash),
  KEY idx_payment_authorization_subject (subject_type, subject_id),
  KEY idx_payment_authorization_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
