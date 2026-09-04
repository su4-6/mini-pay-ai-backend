#!/usr/bin/env bash
set -euo pipefail

# Creates a private production .env and JWT key pair. Never commit them.
umask 077
env_file=${ENV_FILE:-.env.production}
if [[ -e "$env_file" ]]; then
  echo "refusing to overwrite existing $env_file" >&2
  exit 1
fi
cp .env.production.example "$env_file"
mkdir -p .secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .secrets/jwt-private.pem
openssl rsa -pubout -in .secrets/jwt-private.pem -out .secrets/jwt-public.pem

secret() { openssl rand -hex 32; }
key32() { openssl rand -base64 32 | tr -d '\n'; }

cat >> "$env_file" <<EOF

# Server-generated sandbox secrets. Keep this file private.
MYSQL_PASSWORD=$(secret)
SEATA_DB_PASSWORD=$(secret)
SEATA_CONSOLE_PASSWORD=$(secret)
SEATA_SECRET_KEY=$(secret)
RABBITMQ_PASSWORD=$(secret)
MANAGEMENT_OAUTH_CLIENT_SECRET=$(secret)
ADMIN_OAUTH_CLIENT_SECRET=$(secret)
PHONE_HASH_PEPPER=$(secret)
PHONE_DISCLOSURE_ENCRYPTION_KEY=$(key32)
EMAIL_HASH_PEPPER=$(secret)
TOKEN_DIGEST_PEPPER=$(secret)
REAL_NAME_HMAC_KEY=$(secret)
AUTH_AUDIT_PEPPER=$(secret)
CAPTCHA_PEPPER=$(secret)
PAYMENT_TO_IDENTITY_CLIENT_SECRET=$(secret)
PAYMENT_TO_WALLET_CLIENT_SECRET=$(secret)
AGENT_TO_PAYMENT_CLIENT_SECRET=$(secret)
COMMERCE_TO_IDENTITY_CLIENT_SECRET=$(secret)
AGENT_DELEGATION_CLIENT_SECRET=$(secret)
IDENTITY_TO_PAYMENT_CLIENT_SECRET=$(secret)
PAYMENT_AUTHORIZATION_TOKEN_KEY=$(secret)
BANK_SANDBOX_TOKENIZATION_KEY=$(secret)
COLLECTION_CODE_SIGNING_KEY=$(secret)
MERCHANT_APP_SECRET_KEY=$(key32)
COMMERCE_ADDRESS_ENCRYPTION_KEY=$(key32)
AGENT_TO_IDENTITY_CLIENT_SECRET=$(secret)
TURN_SHARED_SECRET=$(secret)
YSHOP_MINIPAY_HMAC_SECRET=$(secret)
YSHOP_MYSQL_PASSWORD=$(secret)
YSHOP_MYSQL_ROOT_PASSWORD=$(secret)
YSHOP_REDIS_PASSWORD=$(secret)
CALLBACK_ADMIN_TOKEN=$(secret)
SESSION_COOKIE_SECURE=true
EOF

chmod 600 "$env_file" .secrets/jwt-private.pem .secrets/jwt-public.pem
echo "created $env_file and .secrets/jwt-*.pem; fill public URLs and provider credentials before deployment"
