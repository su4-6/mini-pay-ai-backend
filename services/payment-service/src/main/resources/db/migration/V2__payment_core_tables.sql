CREATE TABLE transfer_intent (
  intent_id BINARY(16) NOT NULL,
  intent_no VARCHAR(40) NOT NULL,
  payer_user_id BINARY(16) NOT NULL,
  payer_account_id BINARY(16) NOT NULL,
  receiver_user_id BINARY(16) NOT NULL,
  receiver_account_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  remark VARCHAR(128) NULL,
  risk_decision VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (intent_id),
  UNIQUE KEY uk_transfer_intent_no (intent_no),
  KEY idx_transfer_intent_payer_status (payer_user_id, status, created_at),
  KEY idx_transfer_intent_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE transfer_order (
  transfer_id BINARY(16) NOT NULL,
  transfer_no VARCHAR(40) NOT NULL,
  client_request_id VARCHAR(128) NOT NULL,
  intent_id BINARY(16) NOT NULL,
  payer_account_id BINARY(16) NOT NULL,
  receiver_account_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  xid VARCHAR(128) NULL,
  failure_code VARCHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (transfer_id),
  UNIQUE KEY uk_transfer_order_no (transfer_no),
  UNIQUE KEY uk_transfer_order_client_request (client_request_id),
  UNIQUE KEY uk_transfer_order_intent (intent_id),
  KEY idx_transfer_order_status_updated_at (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_order (
  pay_order_id BINARY(16) NOT NULL,
  pay_order_no VARCHAR(40) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  merchant_order_no VARCHAR(128) NOT NULL,
  payer_user_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  subject VARCHAR(256) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (pay_order_id),
  UNIQUE KEY uk_payment_order_no (pay_order_no),
  UNIQUE KEY uk_payment_order_app_merchant_order (app_id, merchant_order_no),
  KEY idx_payment_order_payer_status (payer_user_id, status, created_at),
  KEY idx_payment_order_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refund_order (
  refund_order_id BINARY(16) NOT NULL,
  refund_order_no VARCHAR(40) NOT NULL,
  pay_order_id BINARY(16) NOT NULL,
  merchant_refund_no VARCHAR(128) NOT NULL,
  amount_cent BIGINT NOT NULL,
  reason VARCHAR(256) NULL,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (refund_order_id),
  UNIQUE KEY uk_refund_order_no (refund_order_no),
  UNIQUE KEY uk_refund_order_payment_merchant_refund (pay_order_id, merchant_refund_no),
  KEY idx_refund_order_status_updated_at (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
