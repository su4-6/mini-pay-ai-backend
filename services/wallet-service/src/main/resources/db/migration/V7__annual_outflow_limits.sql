CREATE TABLE annual_outflow_limit (
  owner_id BINARY(16) NOT NULL,
  limit_year SMALLINT NOT NULL,
  limit_amount_cent BIGINT NOT NULL,
  used_amount_cent BIGINT NOT NULL DEFAULT 0,
  reserved_amount_cent BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (owner_id, limit_year),
  CONSTRAINT chk_annual_outflow_non_negative
    CHECK (limit_amount_cent >= 0 AND used_amount_cent >= 0 AND reserved_amount_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE annual_outflow_adjustment (
  adjustment_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  limit_year SMALLINT NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_no VARCHAR(40) NOT NULL,
  adjustment_type VARCHAR(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (adjustment_id),
  UNIQUE KEY uk_annual_outflow_business_adjustment
    (owner_id, business_type, business_no, adjustment_type),
  KEY idx_annual_outflow_owner_year (owner_id, limit_year, created_at),
  CONSTRAINT chk_annual_adjustment_type
    CHECK (adjustment_type IN ('CONSUME', 'RELEASE')),
  CONSTRAINT chk_annual_adjustment_amount CHECK (amount_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE account_freeze
  ADD COLUMN annual_limit_mode VARCHAR(32) NOT NULL DEFAULT 'LIMITED' AFTER amount_cent,
  ADD COLUMN limit_year SMALLINT NULL AFTER annual_limit_mode,
  ADD COLUMN reserved_limit_amount_cent BIGINT NOT NULL DEFAULT 0 AFTER limit_year,
  ADD CONSTRAINT chk_freeze_annual_limit_mode
    CHECK (annual_limit_mode IN ('LIMITED', 'EXEMPT_ACTIVE_CARD')),
  ADD CONSTRAINT chk_freeze_reserved_limit_non_negative
    CHECK (reserved_limit_amount_cent >= 0);
