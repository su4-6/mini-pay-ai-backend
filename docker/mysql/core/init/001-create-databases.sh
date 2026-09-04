#!/usr/bin/env bash
set -euo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
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

CREATE USER IF NOT EXISTS 'minipay'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD:-minipay}';
ALTER USER 'minipay'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD:-minipay}';
GRANT ALL PRIVILEGES ON minipay_identity.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_payment.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_agent.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_wallet.* TO 'minipay'@'%';
GRANT ALL PRIVILEGES ON minipay_commerce.* TO 'minipay'@'%';

CREATE USER IF NOT EXISTS 'minipay_payment_app'@'%' IDENTIFIED BY '${MYSQL_PAYMENT_PASSWORD:-minipay-payment}';
ALTER USER 'minipay_payment_app'@'%' IDENTIFIED BY '${MYSQL_PAYMENT_PASSWORD:-minipay-payment}';
GRANT ALL PRIVILEGES ON minipay_payment.* TO 'minipay_payment_app'@'%';

CREATE USER IF NOT EXISTS 'minipay_wallet_app'@'%' IDENTIFIED BY '${MYSQL_WALLET_PASSWORD:-minipay-wallet}';
ALTER USER 'minipay_wallet_app'@'%' IDENTIFIED BY '${MYSQL_WALLET_PASSWORD:-minipay-wallet}';
GRANT ALL PRIVILEGES ON minipay_wallet.* TO 'minipay_wallet_app'@'%';

CREATE USER IF NOT EXISTS 'seata'@'%' IDENTIFIED BY '${SEATA_DB_PASSWORD:-seata}';
ALTER USER 'seata'@'%' IDENTIFIED BY '${SEATA_DB_PASSWORD:-seata}';
GRANT ALL PRIVILEGES ON seata.* TO 'seata'@'%';
FLUSH PRIVILEGES;
EOSQL
