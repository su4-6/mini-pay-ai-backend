-- Additional application approval workflow.
CREATE TABLE application_apply (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  apply_status VARCHAR(16) NOT NULL,
  reject_reason VARCHAR(200) NULL,
  audit_admin_id VARCHAR(64) NULL,
  resultant_application_id BINARY(16) NULL,
  apply_time DATETIME(6) NOT NULL,
  audit_time DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_apply_user_status (user_id, apply_status),
  KEY idx_apply_status_time (apply_status, apply_time),
  KEY idx_apply_merchant_status (merchant_id, apply_status),
  CONSTRAINT fk_apply_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT fk_apply_result_app
    FOREIGN KEY (resultant_application_id) REFERENCES merchant_application (application_id),
  CONSTRAINT chk_application_apply_status
    CHECK (apply_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPPLEMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
