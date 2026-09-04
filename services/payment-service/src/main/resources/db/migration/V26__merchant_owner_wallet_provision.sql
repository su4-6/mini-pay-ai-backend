CREATE TABLE merchant_owner_wallet_provision (
  owner_user_id BINARY(16) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(6) NULL,
  provisioned_at DATETIME(6) NULL,
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (owner_user_id),
  KEY idx_owner_wallet_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO merchant_owner_wallet_provision (
  owner_user_id, status, attempts, created_at, updated_at
)
SELECT DISTINCT owner_user_id, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM merchant
WHERE owner_user_id IS NOT NULL;
