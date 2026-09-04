CREATE TABLE bank_card (
  card_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_token VARCHAR(255) NOT NULL,
  bank_name VARCHAR(64) NOT NULL,
  card_type VARCHAR(16) NOT NULL,
  masked_card_no VARCHAR(32) NOT NULL,
  last_four CHAR(4) NOT NULL,
  holder_name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  verified_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (card_id),
  UNIQUE KEY uk_bank_card_provider_token (provider, provider_token),
  KEY idx_bank_card_user_status (user_id, status, created_at),
  CONSTRAINT chk_bank_card_type CHECK (card_type IN ('DEBIT')),
  CONSTRAINT chk_bank_card_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE recharge_order
  ADD COLUMN bank_card_id BINARY(16) NULL AFTER amount_cent,
  DROP CHECK chk_recharge_order_channel,
  ADD CONSTRAINT chk_recharge_order_channel
    CHECK (channel IN ('BANK_CARD', 'MINIPAY_SANDBOX'));

CREATE TABLE withdrawal_order (
  withdrawal_id BINARY(16) NOT NULL,
  withdrawal_no VARCHAR(40) NOT NULL,
  user_id BINARY(16) NOT NULL,
  bank_card_id BINARY(16) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  amount_cent BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  bank_request_no VARCHAR(64) NULL,
  failure_code VARCHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (withdrawal_id),
  UNIQUE KEY uk_withdrawal_order_no (withdrawal_no),
  UNIQUE KEY uk_withdrawal_order_idempotency (idempotency_key),
  KEY idx_withdrawal_order_user_created (user_id, created_at DESC),
  KEY idx_withdrawal_order_status_updated (status, updated_at),
  CONSTRAINT chk_withdrawal_order_amount CHECK (amount_cent BETWEEN 1 AND 1000000),
  CONSTRAINT chk_withdrawal_order_status
    CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_attempt (
  attempt_id BINARY(16) NOT NULL,
  pay_order_id BINARY(16) NOT NULL,
  payment_method VARCHAR(32) NOT NULL,
  channel_request_no VARCHAR(64) NOT NULL,
  channel_transaction_no VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL,
  redirect_url VARCHAR(1024) NULL,
  failure_code VARCHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uk_payment_attempt_channel_request (channel_request_no),
  KEY idx_payment_attempt_order_status (pay_order_id, status, created_at),
  CONSTRAINT chk_payment_attempt_method
    CHECK (payment_method IN ('ALIPAY', 'WECHAT_PAY', 'WALLET_BALANCE')),
  CONSTRAINT chk_payment_attempt_status
    CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
