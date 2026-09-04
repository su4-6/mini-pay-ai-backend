ALTER TABLE user_profile
  ADD COLUMN phone_ciphertext VARBINARY(96) NULL AFTER phone_masked,
  ADD COLUMN phone_nonce BINARY(12) NULL AFTER phone_ciphertext,
  ADD COLUMN phone_key_id VARCHAR(32) NULL AFTER phone_nonce,
  ADD COLUMN disclosure_version BIGINT NOT NULL DEFAULT 0 AFTER phone_key_id;

CREATE TABLE external_application (
  application_id VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  developer_name VARCHAR(128) NOT NULL,
  icon_url VARCHAR(512) NULL,
  privacy_policy_url VARCHAR(512) NOT NULL,
  terms_url VARCHAR(512) NOT NULL,
  consent_version INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_application_scope (
  application_id VARCHAR(64) NOT NULL,
  scope_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  purpose VARCHAR(512) NOT NULL,
  required_scope BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (application_id, scope_code),
  CONSTRAINT fk_external_application_scope_application
    FOREIGN KEY (application_id) REFERENCES external_application(application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_application_authorization (
  authorization_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  application_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  consent_version INT NOT NULL,
  authorized_at DATETIME(6) NULL,
  last_used_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (authorization_id),
  UNIQUE KEY uk_user_application_authorization (user_id, application_id),
  KEY idx_application_authorization_user_status (user_id, status, updated_at),
  CONSTRAINT fk_user_application_authorization_application
    FOREIGN KEY (application_id) REFERENCES external_application(application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_application_authorization_scope (
  authorization_id BINARY(16) NOT NULL,
  scope_code VARCHAR(64) NOT NULL,
  granted_at DATETIME(6) NOT NULL,
  PRIMARY KEY (authorization_id, scope_code),
  CONSTRAINT fk_user_application_scope_authorization
    FOREIGN KEY (authorization_id) REFERENCES user_application_authorization(authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE application_authorization_audit (
  audit_id BINARY(16) NOT NULL,
  authorization_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  application_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  scopes_json JSON NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (audit_id),
  KEY idx_application_authorization_audit (user_id, application_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE application_authorization_operation (
  operation_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  application_id VARCHAR(64) NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (operation_id),
  UNIQUE KEY uk_application_authorization_operation
    (user_id, application_id, operation_type, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO external_application (
  application_id, display_name, developer_name, icon_url,
  privacy_policy_url, terms_url, consent_version, status, created_at, updated_at
) VALUES (
  'yshop-food', 'yshop 外卖', 'MiniPay 与 yshop', NULL,
  'https://food.minipay.local/privacy', 'https://food.minipay.local/terms',
  1, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

INSERT INTO external_application_scope (
  application_id, scope_code, display_name, purpose, required_scope, created_at
) VALUES
  ('yshop-food', 'profile.basic', '基本资料', '创建并展示外卖账号的昵称和头像', TRUE, UTC_TIMESTAMP(6)),
  ('yshop-food', 'profile.phone', '完整手机号', '配送联系、自取通知和订单售后', TRUE, UTC_TIMESTAMP(6)),
  ('yshop-food', 'location.current', '当前位置', '查询附近可配送或自取门店', FALSE, UTC_TIMESTAMP(6));
