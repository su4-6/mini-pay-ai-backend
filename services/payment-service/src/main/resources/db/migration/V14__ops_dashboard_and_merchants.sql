-- Operations merchant baseline. Runs after the consumer financial migrations.
CREATE TABLE merchant (
  merchant_id BINARY(16) NOT NULL,
  merchant_no VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  owner_user_id BINARY(16) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (merchant_id),
  UNIQUE KEY uk_merchant_no (merchant_no),
  KEY idx_merchant_name_created (name, created_at),
  KEY idx_merchant_status_created (status, created_at),
  CONSTRAINT chk_merchant_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_application (
  application_id BINARY(16) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (application_id),
  UNIQUE KEY uk_merchant_application_app_id (app_id),
  KEY idx_merchant_application_merchant (merchant_id, created_at),
  CONSTRAINT fk_merchant_application_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
  CONSTRAINT chk_merchant_application_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE payment_order
  ADD COLUMN merchant_id BINARY(16) NULL AFTER pay_order_no,
  ADD COLUMN application_id BINARY(16) NULL AFTER merchant_id,
  ADD KEY idx_payment_order_merchant_created (merchant_id, created_at),
  ADD KEY idx_payment_order_application_created (application_id, created_at);

CREATE TABLE operation_audit (
  audit_id BINARY(16) NOT NULL,
  actor_id VARCHAR(128) NOT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id BINARY(16) NOT NULL,
  before_digest CHAR(64) NULL,
  after_digest CHAR(64) NULL,
  result VARCHAR(16) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (audit_id),
  KEY idx_operation_audit_actor_time (actor_id, occurred_at),
  KEY idx_operation_audit_resource_time (resource_type, resource_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_record (
  record_id BINARY(16) NOT NULL,
  actor_id VARCHAR(128) NOT NULL,
  operation VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  response_status INT NULL,
  response_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (record_id),
  UNIQUE KEY uk_idempotency_actor_operation_key (actor_id, operation, idempotency_key),
  KEY idx_idempotency_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_daily_metric (
  metric_date DATE NOT NULL,
  submitted_payment_count BIGINT NOT NULL DEFAULT 0,
  successful_payment_count BIGINT NOT NULL DEFAULT 0,
  payment_amount_cent BIGINT NOT NULL DEFAULT 0,
  successful_refund_count BIGINT NOT NULL DEFAULT 0,
  refund_amount_cent BIGINT NOT NULL DEFAULT 0,
  calculated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (metric_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_daily_metric (
  metric_date DATE NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  successful_payment_count BIGINT NOT NULL DEFAULT 0,
  payment_amount_cent BIGINT NOT NULL DEFAULT 0,
  successful_refund_count BIGINT NOT NULL DEFAULT 0,
  refund_amount_cent BIGINT NOT NULL DEFAULT 0,
  calculated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (metric_date, merchant_id),
  KEY idx_merchant_daily_metric_merchant_date (merchant_id, metric_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE metric_projected_event (
  event_id BINARY(16) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  projected_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_notification (
  notification_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  application_id BINARY(16) NULL,
  event_id BINARY(16) NOT NULL,
  type VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  next_attempt_at DATETIME(6) NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (notification_id),
  UNIQUE KEY uk_merchant_notification_event_type (merchant_id, event_id, type),
  KEY idx_merchant_notification_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
