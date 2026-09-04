-- Run this script on your local MySQL to create the MiniPay databases.
-- mysql -u root -p < docker/init-local-mysql.sql

CREATE DATABASE IF NOT EXISTS minipay_identity
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS minipay_payment
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS minipay_agent
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS minipay_wallet
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS minipay_commerce
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS seata
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- App user: used by identity/payment/agent/commerce services (default YAML credentials)
CREATE USER IF NOT EXISTS 'minipay'@'%' IDENTIFIED BY 'minipay';
GRANT ALL PRIVILEGES ON minipay_identity.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_payment.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_agent.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_wallet.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_commerce.* TO 'minipay'@'%';

-- Payment app user: used by payment-service default local sandbox credentials.
CREATE USER IF NOT EXISTS 'minipay_payment_app'@'%' IDENTIFIED BY 'minipay-payment';
ALTER USER 'minipay_payment_app'@'%' IDENTIFIED BY 'minipay-payment';
GRANT ALL PRIVILEGES ON minipay_payment.* TO 'minipay_payment_app'@'%';

-- Wallet app user: used by wallet-service (its default YAML credentials)
CREATE USER IF NOT EXISTS 'minipay_wallet_app'@'%' IDENTIFIED BY 'minipay-wallet';
GRANT ALL PRIVILEGES ON minipay_wallet.* TO 'minipay_wallet_app'@'%';

FLUSH PRIVILEGES;

-- Seata tables
USE seata;

CREATE TABLE IF NOT EXISTS global_table (
  xid VARCHAR(128) NOT NULL,
  transaction_id BIGINT,
  status TINYINT NOT NULL,
  application_id VARCHAR(32),
  transaction_service_group VARCHAR(32),
  transaction_name VARCHAR(128),
  timeout INT,
  begin_time BIGINT,
  application_data VARCHAR(2000),
  gmt_create DATETIME,
  gmt_modified DATETIME,
  PRIMARY KEY (xid),
  KEY idx_status_gmt_modified (status, gmt_modified),
  KEY idx_transaction_id (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS branch_table (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  transaction_id BIGINT,
  resource_group_id VARCHAR(32),
  resource_id VARCHAR(256),
  branch_type VARCHAR(8),
  status TINYINT,
  client_id VARCHAR(64),
  application_data VARCHAR(2000),
  gmt_create DATETIME(6),
  gmt_modified DATETIME(6),
  PRIMARY KEY (branch_id),
  KEY idx_xid (xid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lock_table (
  row_key VARCHAR(128) NOT NULL,
  xid VARCHAR(128),
  transaction_id BIGINT,
  branch_id BIGINT NOT NULL,
  resource_id VARCHAR(256),
  table_name VARCHAR(32),
  pk VARCHAR(36),
  status TINYINT NOT NULL DEFAULT 0,
  gmt_create DATETIME,
  gmt_modified DATETIME,
  PRIMARY KEY (row_key),
  KEY idx_status (status),
  KEY idx_branch_id (branch_id),
  KEY idx_xid (xid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS distributed_lock (
  lock_key CHAR(20) NOT NULL,
  lock_value VARCHAR(20) NOT NULL,
  expire BIGINT,
  PRIMARY KEY (lock_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO distributed_lock (lock_key, lock_value, expire)
VALUES ('AsyncCommitting', ' ', 0), ('RetryCommitting', ' ', 0),
       ('RetryRollbacking', ' ', 0), ('TxTimeoutCheck', ' ', 0)
ON DUPLICATE KEY UPDATE lock_value = VALUES(lock_value);

-- NOTE: do not pre-create any application tables here.
-- All app tables (including agent chat tables) are owned by each service's
-- Flyway migrations; a non-empty schema without a flyway_schema_history table
-- makes Flyway refuse to migrate (agent-service startup failure).

SELECT 'MiniPay databases created successfully.' AS status;
