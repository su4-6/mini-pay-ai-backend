#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
env_file=${ENV_FILE:-.env.production}
[[ "$env_file" = /* ]] || env_file="$repo_root/$env_file"
export ENV_FILE="$env_file"

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

: "${FRONTEND_ROOT:?FRONTEND_ROOT is required}"
: "${YSHOP_ROOT:?YSHOP_ROOT is required}"
: "${YSHOP_H5_ROOT:?YSHOP_H5_ROOT is required}"
[[ -f "$YSHOP_H5_ROOT/unpackage/dist/build/h5-minipay/index.html" ]] || {
  echo 'MiniPay food H5 artifact is missing; build it with scripts/build-minipay-h5.ps1 before upload.' >&2
  exit 1
}

bash ./scripts/deploy-server.sh
docker compose --env-file "$env_file" --project-directory "$YSHOP_ROOT" \
  -f "$YSHOP_ROOT/compose.production.yml" config --quiet
docker compose --env-file "$env_file" --project-directory "$FRONTEND_ROOT" \
  -f "$FRONTEND_ROOT/compose.server.yaml" config --quiet
docker compose --env-file "$env_file" --project-directory "$YSHOP_H5_ROOT" \
  -f "$YSHOP_H5_ROOT/compose.production.yml" config --quiet

docker compose --env-file "$env_file" --project-directory "$YSHOP_ROOT" \
  -f "$YSHOP_ROOT/compose.production.yml" up -d --build
docker compose --env-file "$env_file" --project-directory "$FRONTEND_ROOT" \
  -f "$FRONTEND_ROOT/compose.server.yaml" up -d --build
docker compose --env-file "$env_file" --project-directory "$YSHOP_H5_ROOT" \
  -f "$YSHOP_H5_ROOT/compose.production.yml" up -d --build
docker compose --env-file "$env_file" -f compose.edge.yml up -d

docker compose --env-file "$env_file" -f compose.yaml -f compose.production.yml --profile apps ps
docker compose --env-file "$env_file" --project-directory "$YSHOP_ROOT" -f "$YSHOP_ROOT/compose.production.yml" ps
docker compose --env-file "$env_file" --project-directory "$FRONTEND_ROOT" -f "$FRONTEND_ROOT/compose.server.yaml" ps
docker compose --env-file "$env_file" --project-directory "$YSHOP_H5_ROOT" -f "$YSHOP_H5_ROOT/compose.production.yml" ps
