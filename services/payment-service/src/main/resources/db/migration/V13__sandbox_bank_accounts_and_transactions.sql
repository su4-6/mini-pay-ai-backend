CREATE TABLE sandbox_bank_account (
  provider_token VARCHAR(255) NOT NULL,
  available_amount_cent BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  single_payment_limit_cent BIGINT NOT NULL,
  daily_payment_limit_cent BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (provider_token),
  CONSTRAINT chk_sandbox_bank_balance CHECK (available_amount_cent >= 0),
  CONSTRAINT chk_sandbox_bank_currency CHECK (currency = 'CNY'),
  CONSTRAINT chk_sandbox_bank_limits CHECK (
    single_payment_limit_cent > 0 AND daily_payment_limit_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sandbox_bank_transaction (
  transaction_id BINARY(16) NOT NULL,
  provider_token VARCHAR(255) NOT NULL,
  request_no VARCHAR(128) NOT NULL,
  transaction_type VARCHAR(32) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  description VARCHAR(128) NOT NULL,
  amount_cent BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  failure_code VARCHAR(64) NULL,
  occurred_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (transaction_id),
  UNIQUE KEY uk_sandbox_bank_request (provider_token, request_no),
  KEY idx_sandbox_bank_transaction_time (provider_token, occurred_at DESC),
  CONSTRAINT chk_sandbox_bank_transaction_direction CHECK (direction IN ('INCOME', 'EXPENSE')),
  CONSTRAINT chk_sandbox_bank_transaction_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
  CONSTRAINT chk_sandbox_bank_transaction_amount CHECK (amount_cent > 0),
  CONSTRAINT fk_sandbox_bank_transaction_account FOREIGN KEY (provider_token)
    REFERENCES sandbox_bank_account (provider_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
