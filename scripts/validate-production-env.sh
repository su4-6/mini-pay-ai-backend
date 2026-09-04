#!/usr/bin/env bash
set -euo pipefail

env_file=${ENV_FILE:-.env.production}
[[ -f "$env_file" ]] || { echo "missing production environment: $env_file" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

required=(
  IDENTITY_PUBLIC_URL MANAGEMENT_PUBLIC_URL ADMIN_PUBLIC_URL MERCHANT_PUBLIC_URL FOOD_PUBLIC_ORIGIN
  IDENTITY_DOMAIN PAYMENT_DOMAIN WALLET_DOMAIN AGENT_DOMAIN COMMERCE_DOMAIN
  MANAGEMENT_DOMAIN ADMIN_DOMAIN MERCHANT_DOMAIN FOOD_DOMAIN
  MYSQL_PASSWORD SEATA_DB_PASSWORD SEATA_CONSOLE_PASSWORD SEATA_SECRET_KEY RABBITMQ_PASSWORD
  MANAGEMENT_OAUTH_CLIENT_SECRET ADMIN_OAUTH_CLIENT_SECRET PHONE_HASH_PEPPER EMAIL_HASH_PEPPER
  TOKEN_DIGEST_PEPPER REAL_NAME_HMAC_KEY AUTH_AUDIT_PEPPER CAPTCHA_PEPPER
  PAYMENT_TO_IDENTITY_CLIENT_SECRET PAYMENT_TO_WALLET_CLIENT_SECRET AGENT_TO_PAYMENT_CLIENT_SECRET
  COMMERCE_TO_IDENTITY_CLIENT_SECRET AGENT_DELEGATION_CLIENT_SECRET IDENTITY_TO_PAYMENT_CLIENT_SECRET
  PAYMENT_AUTHORIZATION_TOKEN_KEY COLLECTION_CODE_SIGNING_KEY MERCHANT_APP_SECRET_KEY
  COMMERCE_ADDRESS_ENCRYPTION_KEY AGENT_TO_IDENTITY_CLIENT_SECRET TURN_SHARED_SECRET
  YSHOP_MINIPAY_HMAC_SECRET YSHOP_MYSQL_PASSWORD YSHOP_MYSQL_ROOT_PASSWORD YSHOP_REDIS_PASSWORD
  JWT_SIGNING_PRIVATE_KEY_LOCATION JWT_SIGNING_PUBLIC_KEY_LOCATION
  JWT_SIGNING_PRIVATE_KEY_FILE JWT_SIGNING_PUBLIC_KEY_FILE
)

failed=0
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "missing required value: $name" >&2
    failed=1
  fi
done
for name in JWT_SIGNING_PRIVATE_KEY_FILE JWT_SIGNING_PUBLIC_KEY_FILE; do
  if [[ -n "${!name:-}" && ! -f "${!name}" ]]; then
    echo "key file does not exist: $name=${!name}" >&2
    failed=1
  fi
done
for name in IDENTITY_PUBLIC_URL MANAGEMENT_PUBLIC_URL ADMIN_PUBLIC_URL MERCHANT_PUBLIC_URL FOOD_PUBLIC_ORIGIN; do
  if [[ -n "${!name:-}" && ! "${!name}" =~ ^https:// ]]; then
    echo "$name must use https://" >&2
    failed=1
  fi
done
if [[ "${SMS_PROVIDER:-}" == demo || "${REAL_NAME_PROVIDER:-}" == sandbox ]]; then
  echo 'demo SMS and sandbox real-name providers are forbidden in production' >&2
  failed=1
fi
if grep -Eqi '(^|=).*(change-me|demo-secret|123456)' "$env_file"; then
  echo 'production environment still contains demo/change-me values' >&2
  failed=1
fi
if grep -Eqi '(^|[./])example\.com([/:]|$)' "$env_file"; then
  echo 'replace every example.com domain before production deployment' >&2
  failed=1
fi
if [[ "${MODEL_ENABLED:-false}" == true && -z "${MODEL_API_KEY:-}" ]]; then
  echo 'MODEL_API_KEY is required when MODEL_ENABLED=true' >&2
  failed=1
fi
if [[ "${SMS_PROVIDER:-}" == dypns ]]; then
  for name in ALIBABA_CLOUD_ACCESS_KEY_ID ALIBABA_CLOUD_ACCESS_KEY_SECRET ALIYUN_DYPNS_SIGN_NAME; do
    if [[ -z "${!name:-}" ]]; then
      echo "$name is required for SMS_PROVIDER=dypns" >&2
      failed=1
    fi
  done
fi
if [[ "${OBJECT_STORAGE_PROVIDER:-}" == aliyun ]]; then
  for name in ALIBABA_CLOUD_ACCESS_KEY_ID ALIBABA_CLOUD_ACCESS_KEY_SECRET ALIYUN_OSS_ENDPOINT ALIYUN_OSS_REGION ALIYUN_OSS_BUCKET; do
    if [[ -z "${!name:-}" ]]; then
      echo "$name is required for OBJECT_STORAGE_PROVIDER=aliyun" >&2
      failed=1
    fi
  done
fi
(( failed == 0 )) || exit 1

docker compose --env-file "$env_file" -f compose.yaml -f compose.production.yml --profile apps config --quiet
echo 'production environment and Compose configuration are valid'
