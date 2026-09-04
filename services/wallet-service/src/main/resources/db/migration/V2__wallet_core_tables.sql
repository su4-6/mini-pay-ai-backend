CREATE TABLE wallet_account (
  account_id BINARY(16) NOT NULL,
  account_no VARCHAR(40) NOT NULL,
  owner_type VARCHAR(32) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  available_amount_cent BIGINT NOT NULL DEFAULT 0,
  frozen_amount_cent BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_wallet_account_no (account_no),
  UNIQUE KEY uk_wallet_account_owner (owner_type, owner_id),
  CONSTRAINT chk_wallet_account_available_non_negative CHECK (available_amount_cent >= 0),
  CONSTRAINT chk_wallet_account_frozen_non_negative CHECK (frozen_amount_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_freeze (
  freeze_id BINARY(16) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  branch_id BIGINT NOT NULL,
  business_no VARCHAR(40) NOT NULL,
  account_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (freeze_id),
  UNIQUE KEY uk_account_freeze_tcc (xid, branch_id),
  UNIQUE KEY uk_account_freeze_business_account (business_no, account_id),
  KEY idx_account_freeze_account_status (account_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pending_credit (
  credit_id BINARY(16) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  branch_id BIGINT NOT NULL,
  business_no VARCHAR(40) NOT NULL,
  account_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (credit_id),
  UNIQUE KEY uk_pending_credit_tcc (xid, branch_id),
  UNIQUE KEY uk_pending_credit_business_account (business_no, account_id),
  KEY idx_pending_credit_account_status (account_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ledger_transaction (
  transaction_id BINARY(16) NOT NULL,
  transaction_no VARCHAR(40) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_no VARCHAR(40) NOT NULL,
  debit_total_amount_cent BIGINT NOT NULL,
  credit_total_amount_cent BIGINT NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (transaction_id),
  UNIQUE KEY uk_ledger_transaction_no (transaction_no),
  UNIQUE KEY uk_ledger_transaction_business (business_type, business_no),
  CONSTRAINT chk_ledger_transaction_balanced CHECK (debit_total_amount_cent = credit_total_amount_cent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ledger_entry (
  entry_id BINARY(16) NOT NULL,
  transaction_id BINARY(16) NOT NULL,
  account_id BINARY(16) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount_cent BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (entry_id),
  KEY idx_ledger_entry_transaction (transaction_id),
  KEY idx_ledger_entry_account_created_at (account_id, created_at),
  CONSTRAINT chk_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
  CONSTRAINT chk_ledger_entry_amount_positive CHECK (amount_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
