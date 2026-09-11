#!/usr/bin/env bash
# Sent through stdin. Credentials never enter command arguments.
set -Eeuo pipefail
host="${VERIFY_HOST:-127.0.0.1}"
port="${VERIFY_PORT:-3306}"
query() {
  mysql --protocol=TCP --host="$host" --port="$port" --connect-timeout=5 \
    --user="$username" --database="$database" --batch --skip-column-names "$@"
}
if [[ "${VERIFY_KIND:-minipay}" == yshop ]]; then
  username="${MYSQL_USER:?}"
  database="${MYSQL_DATABASE:?}"
  export MYSQL_PWD="${MYSQL_PASSWORD:?}"
  query --execute='SELECT 1;' >/dev/null
  for table in system_users yshop_minipay_external_identity yshop_minipay_checkout_quote \
    yshop_minipay_payment yshop_minipay_event_inbox yshop_minipay_refund_outbox \
    yshop_minipay_address_location_draft; do
    query --execute="SELECT 1 FROM \`$table\` LIMIT 0;" >/dev/null
  done
  query --execute='SELECT profile_version, last_profile_synced_at FROM yshop_minipay_external_identity LIMIT 0;' >/dev/null
  client_count="$(query --execute="SELECT COUNT(client_id) FROM system_oauth2_client WHERE client_id='minipay-food-h5' AND deleted=b'0';")"
  [[ "$client_count" == 1 ]] || { echo '[FAIL] YShop MiniPay OAuth client is missing or duplicated' >&2; exit 1; }
  echo '[PASS] YShop login and required tables'
  exit 0
fi
for entry in IDENTITY:minipay_identity PAYMENT:minipay_payment WALLET:minipay_wallet \
  COMMERCE:minipay_commerce AGENT:minipay_agent SEATA:seata; do
  prefix="${entry%%:*}"
  database="${entry#*:}"
  user_key="${prefix}_DB_USERNAME"
  password_key="${prefix}_DB_PASSWORD"
  username="${!user_key}"
  export MYSQL_PWD="${!password_key}"
  query --execute='SELECT 1;' >/dev/null
  for other in minipay_identity minipay_payment minipay_wallet minipay_commerce minipay_agent seata mysql; do
    [[ "$other" == "$database" ]] && continue
    if query --database="$other" --execute='SELECT 1;' >/dev/null 2>&1; then
      echo "[FAIL] $prefix unexpectedly has access to $other" >&2
      exit 1
    fi
  done
  if [[ "$database" == seata ]]; then
    for table in global_table branch_table lock_table distributed_lock vgroup_table; do
      query --execute="SELECT 1 FROM \`$table\` LIMIT 0;" >/dev/null
    done
  fi
  echo "[PASS] $prefix database login and schema isolation"
done
echo '[PASS] Seata tables exist; TCC business behavior still requires Payment/Wallet tests'
