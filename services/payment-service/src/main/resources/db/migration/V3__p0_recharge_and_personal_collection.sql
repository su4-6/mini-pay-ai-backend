CREATE TABLE recharge_order (
  recharge_id BINARY(16) NOT NULL,
  recharge_no VARCHAR(40) NOT NULL,
  user_id BINARY(16) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  amount_cent BIGINT NOT NULL,
  channel VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  failure_code VARCHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (recharge_id),
  UNIQUE KEY uk_recharge_order_no (recharge_no),
  UNIQUE KEY uk_recharge_order_idempotency (idempotency_key),
  KEY idx_recharge_order_user_created (user_id, created_at DESC),
  CONSTRAINT chk_recharge_order_amount CHECK (amount_cent BETWEEN 1 AND 1000000),
  CONSTRAINT chk_recharge_order_channel CHECK (channel = 'MINIPAY_SANDBOX'),
  CONSTRAINT chk_recharge_order_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collection_code (
  code_id BINARY(16) NOT NULL,
  owner_user_id BINARY(16) NOT NULL,
  code_type VARCHAR(32) NOT NULL,
  token_nonce VARCHAR(64) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (code_id),
  UNIQUE KEY uk_collection_code_token_hash (token_hash),
  KEY idx_collection_code_owner_status (owner_user_id, status, expires_at),
  CONSTRAINT chk_collection_code_type CHECK (code_type = 'PERSONAL_COLLECTION'),
  CONSTRAINT chk_collection_code_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
