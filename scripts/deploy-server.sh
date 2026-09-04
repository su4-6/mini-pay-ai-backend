#!/usr/bin/env bash
set -euo pipefail

# The server's .env is deliberately not in Git. Generate it with
# bootstrap-server-env.sh, fill provider credentials, and keep chmod 600.
env_file=${ENV_FILE:-.env.production}
compose=(docker compose --env-file "$env_file" -f compose.yaml -f compose.production.yml --profile apps)

ENV_FILE="$env_file" bash ./scripts/validate-production-env.sh
env -u JAVA_HOME bash ./mvnw clean -DskipTests package
"${compose[@]}" pull mysql redis rabbitmq seata-server
"${compose[@]}" up -d \
  mysql redis rabbitmq seata-server
"${compose[@]}" up -d --build \
  mysql redis rabbitmq seata-server \
  identity-service wallet-service payment-service commerce-service agent-service \
  consumer-bff management-bff admin-bff

"${compose[@]}" ps
