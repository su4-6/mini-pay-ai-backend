#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${SEATA_DB_PASSWORD:-}" ]]; then
  echo "SEATA_DB_PASSWORD must be set for the local MySQL bootstrap" >&2
  exit 1
fi

# Pass the password as hex and let MySQL quote it. This keeps arbitrary secret
# characters out of shell-expanded SQL literals.
password_hex="$(printf '%s' "${SEATA_DB_PASSWORD}" | od -An -tx1 | tr -d ' \n')"

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot <<SQL
SET @seata_password = CONVERT(0x${password_hex} USING utf8mb4);
SET @create_user_sql = CONCAT(
  "CREATE USER IF NOT EXISTS 'seata'@'%' IDENTIFIED BY ",
  QUOTE(@seata_password)
);
PREPARE create_user_statement FROM @create_user_sql;
EXECUTE create_user_statement;
DEALLOCATE PREPARE create_user_statement;
SET @alter_user_sql = CONCAT(
  "ALTER USER 'seata'@'%' IDENTIFIED BY ",
  QUOTE(@seata_password)
);
PREPARE alter_user_statement FROM @alter_user_sql;
EXECUTE alter_user_statement;
DEALLOCATE PREPARE alter_user_statement;
GRANT ALL PRIVILEGES ON seata.* TO 'seata'@'%';
FLUSH PRIVILEGES;
SQL
