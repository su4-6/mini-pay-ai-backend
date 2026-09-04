#!/usr/bin/env bash
set -euo pipefail

release_dir="${MINIPAY_RELEASE_DIR:-/home/ubuntu/apps/minipay-current}"
env_file="${release_dir}/.env.deploy"
admin_token="$(sed -n 's/^CALLBACK_ADMIN_TOKEN=//p' "${env_file}" | tail -n 1 | tr -d '\r')"

if [[ -z "${admin_token}" ]]; then
  echo "CALLBACK_ADMIN_TOKEN is missing" >&2
  exit 1
fi

app_id="acceptance_probe_20260808"
app_secret="$(openssl rand -hex 32)"
timestamp="$(date +%s%3N)"
nonce="acceptance-${timestamp}"
event_id="acceptance-event-${timestamp}"
merchant_order_no="acceptance-order-${timestamp}"
payment_order_no="P-acceptance-${timestamp}"
amount_cent="1"

register_payload="$(APP_ID="${app_id}" APP_SECRET="${app_secret}" python3 -c \
  'import json, os; print(json.dumps({"appId": os.environ["APP_ID"], "appSecret": os.environ["APP_SECRET"]}))')"

curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8099/internal/v1/apps \
  --header "Authorization: Bearer ${admin_token}" \
  --header "Content-Type: application/json" \
  --data-binary "${register_payload}" >/dev/null

signing_source="$(printf 'PAYMENT\n%s\n%s\n%s\n%s\n%s\n%s' \
  "${app_id}" "${merchant_order_no}" "${payment_order_no}" "${amount_cent}" "${timestamp}" "${nonce}")"
signature="$(printf '%s' "${signing_source}" | openssl dgst -sha256 -hmac "${app_secret}" -hex | awk '{print $NF}')"

payload="$(APP_ID="${app_id}" EVENT_ID="${event_id}" MERCHANT_ORDER_NO="${merchant_order_no}" \
  PAYMENT_ORDER_NO="${payment_order_no}" AMOUNT_CENT="${amount_cent}" TIMESTAMP="${timestamp}" NONCE="${nonce}" \
  python3 -c 'import json, os; print(json.dumps({
    "eventId": os.environ["EVENT_ID"], "eventType": "payment.succeeded",
    "appId": os.environ["APP_ID"], "merchantOrderNo": os.environ["MERCHANT_ORDER_NO"],
    "paymentOrderNo": os.environ["PAYMENT_ORDER_NO"], "amountCent": int(os.environ["AMOUNT_CENT"]),
    "timestamp": int(os.environ["TIMESTAMP"]), "nonce": os.environ["NONCE"]
  }))')"

send_callback() {
  curl --fail-with-body --silent --show-error \
    --request POST http://127.0.0.1:8099/payment/notify \
    --header "Content-Type: application/json" \
    --header "X-MiniPay-Signature: ${signature}" \
    --header "X-MiniPay-Timestamp: ${timestamp}" \
    --header "X-MiniPay-Nonce: ${nonce}" \
    --data-binary "${payload}"
}

first="$(send_callback)"
second="$(send_callback)"
unset app_secret admin_token register_payload signing_source signature payload
echo "first=${first}"
echo "second=${second}"
