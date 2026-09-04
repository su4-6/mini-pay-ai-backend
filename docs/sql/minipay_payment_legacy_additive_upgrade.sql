-- One-time additive upgrade for the pre-v4 local minipay_payment schema.
-- Source of truth: payment-service Flyway V12-V15. No DROP TABLE/DELETE/TRUNCATE.
-- Existing merchant/application rows are preserved. Legacy application secrets cannot be reconstructed.

ALTER TABLE merchant
  ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT '其他' AFTER name,
  ADD COLUMN contact_name VARCHAR(64) NULL AFTER category,
  ADD COLUMN contact_mobile VARCHAR(16) NULL AFTER contact_name,
  ADD COLUMN source VARCHAR(24) NOT NULL DEFAULT 'LEGACY' AFTER contact_mobile,
  ADD COLUMN onboarding_id BINARY(16) NULL AFTER source,
  ADD COLUMN agreement_version VARCHAR(16) NULL AFTER onboarding_id,
  ADD COLUMN agreed_at DATETIME(6) NULL AFTER agreement_version,
  ADD COLUMN receive_locked BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
  ADD COLUMN remark VARCHAR(200) NULL AFTER receive_locked,
  ADD COLUMN default_application_id BINARY(16) NULL AFTER remark;

ALTER TABLE merchant_application
  ADD COLUMN app_id VARCHAR(48) NULL AFTER application_id,
  ADD COLUMN app_secret_ciphertext VARBINARY(512) NULL AFTER app_id,
  ADD COLUMN secret_key_version BIGINT NOT NULL DEFAULT 1 AFTER app_secret_ciphertext,
  ADD COLUMN secret_rotated_at DATETIME(6) NULL AFTER secret_key_version,
  ADD COLUMN app_name VARCHAR(64) NULL AFTER merchant_id,
  ADD COLUMN app_type VARCHAR(16) NOT NULL DEFAULT 'SELF_USE' AFTER app_name,
  ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE AFTER app_type,
  ADD COLUMN notify_url VARCHAR(512) NULL AFTER is_default,
  ADD COLUMN refund_notify_url VARCHAR(512) NULL AFTER notify_url,
  ADD COLUMN ip_white_list VARCHAR(1000) NULL AFTER refund_notify_url,
  ADD COLUMN api_permissions VARCHAR(256) NULL AFTER ip_white_list,
  ADD COLUMN available_channels VARCHAR(64) NULL AFTER api_permissions;

-- Preserve legacy identifiers and labels; no new AppId, secret, merchant or order is invented.
UPDATE merchant_application
SET app_id = minipay_app_id
WHERE app_id IS NULL;

UPDATE merchant_application
SET app_name = name
WHERE app_name IS NULL;

UPDATE merchant_application
SET api_permissions = 'PAYMENT,ORDER_QUERY'
WHERE api_permissions IS NULL;

UPDATE merchant_application
SET available_channels = 'WALLET,ALIPAY,WECHAT'
WHERE available_channels IS NULL;

CREATE UNIQUE INDEX uk_merchant_app_id ON merchant_application (app_id);
CREATE INDEX idx_merchant_application ON merchant_application (merchant_id, created_at);

CREATE TABLE IF NOT EXISTS merchant_onboarding (
  onboarding_id BINARY(16) NOT NULL, merchant_id BINARY(16) NOT NULL, applicant_user_id BINARY(16) NOT NULL,
  applicant_mobile_masked VARCHAR(32) NULL, merchant_name VARCHAR(64) NOT NULL, category VARCHAR(32) NOT NULL,
  contact_name VARCHAR(64) NULL, contact_mobile VARCHAR(16) NULL, agreement_version VARCHAR(16) NOT NULL,
  protocol_accepted BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(16) NOT NULL, reject_reason VARCHAR(200) NULL,
  reviewer_id VARCHAR(64) NULL, reviewed_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(onboarding_id), KEY idx_merchant_onboarding(status, created_at),
  KEY idx_merchant_onboarding_merchant_created(merchant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_credential (
  credential_id BINARY(16) NOT NULL, merchant_id BINARY(16) NOT NULL, login_account VARCHAR(32) NOT NULL,
  password_hash VARCHAR(255) NOT NULL, failed_attempts INT NOT NULL DEFAULT 0, locked_until DATETIME(6) NULL,
  invalidated_at DATETIME(6) NULL, invalidated_reason VARCHAR(32) NULL, last_changed_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(credential_id), UNIQUE KEY uk_merchant_credential(merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_collection_code (
  code_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, token_hash BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', key_version BIGINT NOT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(code_id),
  UNIQUE KEY uk_merchant_code_application(application_id), UNIQUE KEY uk_merchant_code_token(token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_scan_resolution (
  resolution_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, merchant_id BINARY(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL, PRIMARY KEY(resolution_id),
  KEY idx_merchant_resolution_expiry(expires_at), KEY idx_merchant_resolution_application_expiry(application_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_daily_metric (
  merchant_id BINARY(16) NOT NULL, stat_date DATE NOT NULL, payment_amount_cent BIGINT NOT NULL DEFAULT 0,
  payment_count BIGINT NOT NULL DEFAULT 0, refund_amount_cent BIGINT NOT NULL DEFAULT 0, refund_count BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(merchant_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_inbox_message (
  event_id VARCHAR(64) NOT NULL, consumer_name VARCHAR(64) NOT NULL, payload_digest BINARY(32) NOT NULL,
  processed_at DATETIME(6) NOT NULL, PRIMARY KEY(event_id, consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_notification (
  notification_id BINARY(16) NOT NULL, merchant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
  event_id VARCHAR(64) NOT NULL, type VARCHAR(16) NOT NULL, event_type VARCHAR(64) NULL,
  merchant_order_no VARCHAR(128) NULL, business_no VARCHAR(64) NULL, refund_business_no VARCHAR(64) NULL,
  amount_cent BIGINT NULL, occurred_at DATETIME(6) NULL, status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NULL, request_summary VARCHAR(512) NULL, response_summary VARCHAR(512) NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(notification_id),
  UNIQUE KEY uk_merchant_notification(application_id,event_id,type),
  KEY idx_merchant_notification(status,next_attempt_at), KEY idx_merchant_notification_merchant_created(merchant_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_notification_attempt (
  attempt_id BINARY(16) NOT NULL, notification_id BINARY(16) NOT NULL, attempt_no INT NOT NULL,
  http_status INT NULL, result VARCHAR(24) NOT NULL, request_summary VARCHAR(512) NULL,
  response_summary VARCHAR(512) NULL, body_digest BINARY(32) NULL, occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY(attempt_id), UNIQUE KEY uk_merchant_notification_attempt(notification_id,attempt_no),
  KEY idx_merchant_notification_attempt_notification(notification_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_operation_audit (
  audit_id BINARY(16) NOT NULL, merchant_id BINARY(16) NULL, actor_id VARCHAR(64) NOT NULL,
  actor_type VARCHAR(24) NOT NULL, operation VARCHAR(64) NOT NULL, resource_type VARCHAR(32) NOT NULL,
  resource_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NULL, request_id VARCHAR(128) NULL,
  before_summary VARCHAR(512) NULL, after_summary VARCHAR(512) NULL, result VARCHAR(24) NOT NULL,
  occurred_at DATETIME(6) NOT NULL, PRIMARY KEY(audit_id),
  KEY idx_merchant_operation_audit_merchant_time(merchant_id,occurred_at),
  KEY idx_merchant_operation_audit_actor_time(actor_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE payment_order
  ADD COLUMN merchant_no VARCHAR(32) NULL AFTER merchant_order_no,
  ADD COLUMN allowed_channels VARCHAR(64) NULL AFTER channel;

CREATE INDEX idx_payment_order_merchant_created ON payment_order (merchant_no, created_at);
CREATE UNIQUE INDEX uk_payment_order_merchant_app_order
  ON payment_order (merchant_no, app_id, merchant_order_no);
