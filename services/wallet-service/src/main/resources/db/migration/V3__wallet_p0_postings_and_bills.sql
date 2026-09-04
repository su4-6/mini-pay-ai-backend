ALTER TABLE wallet_account
  ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'CNY' AFTER owner_id,
  ADD COLUMN account_role VARCHAR(32) NOT NULL DEFAULT 'CONSUMER_WALLET' AFTER currency,
  ADD CONSTRAINT chk_wallet_account_currency CHECK (currency = 'CNY'),
  ADD CONSTRAINT chk_wallet_account_status CHECK (status IN ('ACTIVE', 'SUSPENDED'));

CREATE TABLE wallet_bill (
  bill_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  account_id BINARY(16) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_no VARCHAR(40) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount_cent BIGINT NOT NULL,
  counterparty_display VARCHAR(128) NULL,
  remark VARCHAR(50) NULL,
  status VARCHAR(16) NOT NULL,
  balance_after_cent BIGINT NULL,
  failure_code VARCHAR(64) NULL,
  occurred_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (bill_id),
  UNIQUE KEY uk_wallet_bill_owner_business (owner_id, business_type, business_no),
  KEY idx_wallet_bill_owner_occurred (owner_id, occurred_at DESC),
  KEY idx_wallet_bill_owner_filters (owner_id, direction, business_type, status, occurred_at),
  CONSTRAINT chk_wallet_bill_direction CHECK (direction IN ('INCOME', 'EXPENSE')),
  CONSTRAINT chk_wallet_bill_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT chk_wallet_bill_amount_positive CHECK (amount_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_posting_idempotency (
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_no VARCHAR(40) NOT NULL,
  result_bill_id BINARY(16) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (idempotency_key),
  UNIQUE KEY uk_wallet_posting_business (business_type, business_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Fixed internal accounts are identical on both physical shards. They are
-- deliberately overfunded virtual counterparts; no real funds are represented.
INSERT INTO wallet_account (
  account_id, account_no, owner_type, owner_id, currency, account_role,
  available_amount_cent, frozen_amount_cent, status, version, created_at, updated_at
) VALUES
  (UNHEX('00000000000070008000000000000001'), 'SYS_SANDBOX_ISSUANCE', 'SYSTEM',
   UNHEX('00000000000070008000000000000101'), 'CNY', 'SANDBOX_ISSUANCE',
   9000000000000000, 0, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX('00000000000070008000000000000002'), 'SYS_INTERSHARD_CLEARING', 'SYSTEM',
   UNHEX('00000000000070008000000000000102'), 'CNY', 'INTERSHARD_CLEARING',
   9000000000000000, 0, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
