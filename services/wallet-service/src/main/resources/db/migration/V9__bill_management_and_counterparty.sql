ALTER TABLE wallet_bill
  ADD COLUMN counterparty_user_id BINARY(16) NULL AFTER counterparty_display,
  ADD KEY idx_wallet_bill_counterparty (counterparty_user_id);

ALTER TABLE account_freeze
  ADD COLUMN counterparty_user_id BINARY(16) NULL AFTER account_id;

ALTER TABLE pending_credit
  ADD COLUMN counterparty_user_id BINARY(16) NULL AFTER account_id;

CREATE TABLE wallet_bill_management (
  bill_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  category_code VARCHAR(24) NOT NULL,
  user_note VARCHAR(200) NULL,
  include_in_statistics BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (bill_id),
  KEY idx_bill_management_owner (owner_id, updated_at DESC),
  CONSTRAINT chk_bill_management_category CHECK (category_code IN (
    'TRANSFER', 'FUNDING', 'DINING', 'SHOPPING', 'TRANSPORT', 'LIFE_SERVICE',
    'MEDICAL', 'EDUCATION', 'ENTERTAINMENT', 'REFUND', 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_user_tag (
  tag_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  name VARCHAR(48) NOT NULL,
  normalized_name VARCHAR(48) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tag_id),
  UNIQUE KEY uk_wallet_tag_owner_name (owner_id, normalized_name),
  UNIQUE KEY uk_wallet_tag_owner_idempotency (owner_id, idempotency_key),
  KEY idx_wallet_tag_owner_created (owner_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_bill_tag_assignment (
  owner_id BINARY(16) NOT NULL,
  bill_id BINARY(16) NOT NULL,
  tag_id BINARY(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (bill_id, tag_id),
  KEY idx_bill_tag_owner (owner_id, bill_id),
  KEY idx_bill_tag_tag_owner (tag_id, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
