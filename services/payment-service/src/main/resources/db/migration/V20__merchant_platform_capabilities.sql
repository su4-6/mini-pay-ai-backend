-- Merchant B-end platform capabilities are additive to the operations-owned
-- merchant/application baseline. Existing operation fields and states remain intact.

ALTER TABLE merchant
  ADD COLUMN source VARCHAR(24) NOT NULL DEFAULT 'OPS' AFTER owner_user_id,
  ADD COLUMN default_application_id BINARY(16) NULL AFTER source,
  ADD COLUMN initialized_at DATETIME(6) NULL AFTER default_application_id,
  ADD KEY idx_merchant_owner (owner_user_id),
  ADD KEY idx_merchant_owner_status (owner_user_id, status, created_at);

ALTER TABLE merchant_application
  ADD COLUMN app_type VARCHAR(16) NOT NULL DEFAULT 'SELF_USE' AFTER name,
  ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE AFTER app_type,
  ADD COLUMN app_secret_ciphertext VARBINARY(512) NULL AFTER is_default,
  ADD COLUMN secret_key_version BIGINT NOT NULL DEFAULT 1 AFTER app_secret_ciphertext,
  ADD COLUMN secret_first_viewed_at DATETIME(6) NULL AFTER secret_key_version,
  ADD COLUMN secret_rotated_at DATETIME(6) NULL AFTER secret_first_viewed_at,
  ADD COLUMN notify_url VARCHAR(512) NULL AFTER secret_rotated_at,
  ADD COLUMN refund_notify_url VARCHAR(512) NULL AFTER notify_url,
  ADD COLUMN ip_white_list VARCHAR(1000) NULL AFTER refund_notify_url,
  ADD COLUMN api_permissions VARCHAR(512) NOT NULL DEFAULT 'PAYMENT_CREATE,PAYMENT_QUERY,REFUND_CREATE' AFTER ip_white_list,
  ADD COLUMN available_channels VARCHAR(128) NOT NULL DEFAULT 'WALLET,ALIPAY,WECHAT' AFTER api_permissions,
  ADD COLUMN configured_at DATETIME(6) NULL AFTER available_channels,
  ADD KEY idx_merchant_application_status (merchant_id, status, created_at);

CREATE TABLE merchant_collection_code (
  code_id BINARY(16) NOT NULL,
  application_id BINARY(16) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  token_ciphertext VARBINARY(512) NOT NULL,
  key_version BIGINT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (code_id),
  UNIQUE KEY uk_merchant_code_application (application_id),
  UNIQUE KEY uk_merchant_code_token_hash (token_hash),
  CONSTRAINT fk_merchant_code_application
    FOREIGN KEY (application_id) REFERENCES merchant_application (application_id) ON DELETE RESTRICT,
  CONSTRAINT chk_merchant_code_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_scan_resolution (
  resolution_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  application_id BINARY(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  consumed_by_order_id BINARY(16) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (resolution_id),
  KEY idx_merchant_resolution_expiry (expires_at),
  KEY idx_merchant_resolution_application_expiry (application_id, expires_at),
  CONSTRAINT fk_merchant_resolution_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
  CONSTRAINT fk_merchant_resolution_application
    FOREIGN KEY (application_id) REFERENCES merchant_application (application_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE payment_order
  ADD COLUMN resolution_id BINARY(16) NULL AFTER application_id,
  ADD COLUMN client_idempotency_key VARCHAR(128) NULL AFTER resolution_id,
  ADD UNIQUE KEY uk_payment_order_resolution (resolution_id),
  ADD UNIQUE KEY uk_payment_order_payer_idempotency (payer_user_id, client_idempotency_key);

CREATE TABLE merchant_metric_inbox (
  event_id BINARY(16) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload_digest BINARY(32) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_notification_inbox (
  event_id BINARY(16) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload_digest BINARY(32) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE merchant_notification
  ADD COLUMN event_type VARCHAR(128) NULL AFTER type,
  ADD COLUMN merchant_order_no VARCHAR(128) NULL AFTER event_type,
  ADD COLUMN business_no VARCHAR(64) NULL AFTER merchant_order_no,
  ADD COLUMN refund_business_no VARCHAR(64) NULL AFTER business_no,
  ADD COLUMN amount_cent BIGINT NULL AFTER refund_business_no,
  ADD COLUMN occurred_at DATETIME(6) NULL AFTER amount_cent,
  ADD COLUMN request_summary VARCHAR(512) NULL AFTER occurred_at,
  ADD COLUMN response_status INT NULL AFTER request_summary,
  ADD COLUMN response_summary VARCHAR(512) NULL AFTER response_status,
  ADD KEY idx_merchant_notification_due (status, next_attempt_at),
  ADD KEY idx_merchant_notification_merchant_created (merchant_id, created_at);

CREATE TABLE merchant_notification_attempt (
  attempt_id BINARY(16) NOT NULL,
  notification_id BINARY(16) NOT NULL,
  attempt_no INT NOT NULL,
  automated BOOLEAN NOT NULL,
  http_status INT NULL,
  result VARCHAR(24) NOT NULL,
  request_digest BINARY(32) NULL,
  response_digest BINARY(32) NULL,
  request_summary VARCHAR(512) NULL,
  response_summary VARCHAR(512) NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uk_merchant_notification_attempt (notification_id, attempt_no),
  KEY idx_merchant_notification_attempt_time (notification_id, occurred_at),
  CONSTRAINT fk_merchant_notification_attempt_notification
    FOREIGN KEY (notification_id) REFERENCES merchant_notification (notification_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_operation_audit (
  audit_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NULL,
  actor_id VARCHAR(128) NOT NULL,
  actor_type VARCHAR(24) NOT NULL,
  operation VARCHAR(64) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NULL,
  request_id VARCHAR(128) NULL,
  before_summary VARCHAR(512) NULL,
  after_summary VARCHAR(512) NULL,
  result VARCHAR(24) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (audit_id),
  KEY idx_merchant_audit_merchant_time (merchant_id, occurred_at),
  KEY idx_merchant_audit_actor_time (actor_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE merchant_apply
  ADD COLUMN normalized_shop_name VARCHAR(128) NULL AFTER shop_name,
  ADD COLUMN submission_version INT NOT NULL DEFAULT 1 AFTER normalized_shop_name,
  ADD KEY idx_apply_owner_shop_status (user_id, normalized_shop_name, apply_status);
