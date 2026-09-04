-- Merchant lifecycle and onboarding applications.
ALTER TABLE merchant
  ADD COLUMN merchant_type VARCHAR(16) NOT NULL DEFAULT 'PERSONAL' AFTER contact_email,
  ADD COLUMN mcc_code VARCHAR(16) NULL AFTER merchant_type,
  ADD COLUMN address VARCHAR(200) NULL AFTER mcc_code,
  ADD COLUMN shop_images TEXT NULL AFTER address,
  ADD COLUMN freeze_reason VARCHAR(200) NULL AFTER status;

ALTER TABLE merchant
  DROP CHECK chk_merchant_status,
  ADD CONSTRAINT chk_merchant_status CHECK (status IN ('ACTIVE', 'DISABLED', 'FROZEN'));

CREATE TABLE merchant_apply (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BINARY(16) NOT NULL,
  merchant_type VARCHAR(16) NOT NULL,
  shop_name VARCHAR(64) NOT NULL,
  mcc_code VARCHAR(16) NULL,
  address VARCHAR(200) NULL,
  shop_images TEXT NULL,
  contact_name VARCHAR(64) NOT NULL,
  contact_mobile VARCHAR(32) NOT NULL,
  contact_email VARCHAR(254) NULL,
  remark VARCHAR(500) NULL,
  apply_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  reject_reason VARCHAR(200) NULL,
  audit_admin_id VARCHAR(64) NULL,
  resultant_merchant_id BINARY(16) NULL,
  apply_time DATETIME(6) NOT NULL,
  audit_time DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_apply_user_status (user_id, apply_status),
  KEY idx_apply_status_time (apply_status, apply_time),
  CONSTRAINT chk_apply_status
    CHECK (apply_status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'SUPPLEMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
