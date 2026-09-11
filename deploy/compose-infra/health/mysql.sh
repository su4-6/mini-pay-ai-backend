#!/bin/sh
# ping can succeed even after an authentication error. Run an authenticated query.
set -eu
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
mysql --protocol=TCP --host=127.0.0.1 --connect-timeout=5 \
  --user=root --batch --skip-column-names --execute='SELECT 1;' >/dev/null
