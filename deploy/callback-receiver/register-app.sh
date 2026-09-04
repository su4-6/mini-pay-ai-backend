#!/usr/bin/env bash
set -euo pipefail

release_dir="${MINIPAY_RELEASE_DIR:-/home/ubuntu/apps/minipay-current}"
env_file="${release_dir}/.env.deploy"

if [[ ! -r "${env_file}" ]]; then
  echo "Cannot read ${env_file}" >&2
  exit 1
fi

callback_admin_token="$({ sed -n 's/^CALLBACK_ADMIN_TOKEN=//p' "${env_file}" || true; } | tail -n 1 | tr -d '\r')"
if [[ -z "${callback_admin_token}" ]]; then
  echo "CALLBACK_ADMIN_TOKEN is missing from ${env_file}" >&2
  exit 1
fi

read -r -p "AppId: " app_id
read -r -s -p "AppSecret (input hidden): " app_secret
echo

if [[ -z "${app_id}" || -z "${app_secret}" ]]; then
  echo "AppId and AppSecret are required" >&2
  exit 1
fi

payload="$({ APP_ID="${app_id}" APP_SECRET="${app_secret}" python3 -c \
  'import json, os; print(json.dumps({"appId": os.environ["APP_ID"], "appSecret": os.environ["APP_SECRET"]}))'; })"

response="$({ curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8099/internal/v1/apps \
  --header "Authorization: Bearer ${callback_admin_token}" \
  --header "Content-Type: application/json" \
  --data-binary "${payload}"; })"

unset app_secret callback_admin_token payload
echo "Callback secret registered: ${response}"
