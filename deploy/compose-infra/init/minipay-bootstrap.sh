#!/usr/bin/env bash

set -Eeuo pipefail

required_variables=(
  MYSQL_ROOT_PASSWORD
  IDENTITY_DB_USERNAME
  IDENTITY_DB_PASSWORD
  PAYMENT_DB_USERNAME
  PAYMENT_DB_PASSWORD
  WALLET_DB_USERNAME
  WALLET_DB_PASSWORD
  COMMERCE_DB_USERNAME
  COMMERCE_DB_PASSWORD
  AGENT_DB_USERNAME
  AGENT_DB_PASSWORD
  SEATA_DB_USERNAME
  SEATA_DB_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "Required environment variable is missing: ${variable_name}" >&2
    exit 1
  fi
done

if [[ "${SEATA_DB_USERNAME}" != "seata" ]]; then
  echo "SEATA_DB_USERNAME must be seata because the current Seata configuration uses that database user." >&2
  exit 1
fi

to_hex() {
  printf '%s' "$1" | od -An -tx1 | tr -d ' \n'
}

create_database_and_user() {
  local database_name="$1"
  local username="$2"
  local password="$3"

  local username_hex
  local password_hex

  username_hex="$(to_hex "${username}")"
  password_hex="$(to_hex "${password}")"

  MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql \
    --protocol=socket \
    --user=root <<SQL
CREATE DATABASE IF NOT EXISTS ${database_name}
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

SET @app_username = CONVERT(0x${username_hex} USING utf8mb4);
SET @app_password = CONVERT(0x${password_hex} USING utf8mb4);

SET @create_user_sql = CONCAT(
  'CREATE USER IF NOT EXISTS ',
  QUOTE(@app_username),
  '@''%'' IDENTIFIED BY ',
  QUOTE(@app_password)
);

PREPARE create_user_statement FROM @create_user_sql;
EXECUTE create_user_statement;
DEALLOCATE PREPARE create_user_statement;

SET @alter_user_sql = CONCAT(
  'ALTER USER ',
  QUOTE(@app_username),
  '@''%'' IDENTIFIED BY ',
  QUOTE(@app_password)
);

PREPARE alter_user_statement FROM @alter_user_sql;
EXECUTE alter_user_statement;
DEALLOCATE PREPARE alter_user_statement;

SET @grant_sql = CONCAT(
  'GRANT ALL PRIVILEGES ON ${database_name}.* TO ',
  QUOTE(@app_username),
  '@''%'''
);

PREPARE grant_statement FROM @grant_sql;
EXECUTE grant_statement;
DEALLOCATE PREPARE grant_statement;
SQL

  echo "Initialized database: ${database_name}"
}

create_database_and_user \
  "minipay_identity" \
  "${IDENTITY_DB_USERNAME}" \
  "${IDENTITY_DB_PASSWORD}"

create_database_and_user \
  "minipay_payment" \
  "${PAYMENT_DB_USERNAME}" \
  "${PAYMENT_DB_PASSWORD}"

create_database_and_user \
  "minipay_wallet" \
  "${WALLET_DB_USERNAME}" \
  "${WALLET_DB_PASSWORD}"

create_database_and_user \
  "minipay_commerce" \
  "${COMMERCE_DB_USERNAME}" \
  "${COMMERCE_DB_PASSWORD}"

create_database_and_user \
  "minipay_agent" \
  "${AGENT_DB_USERNAME}" \
  "${AGENT_DB_PASSWORD}"

create_database_and_user \
  "seata" \
  "${SEATA_DB_USERNAME}" \
  "${SEATA_DB_PASSWORD}"

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql \
  --protocol=socket \
  --user=root \
  --execute="FLUSH PRIVILEGES;"

echo "MiniPay database bootstrap completed."
