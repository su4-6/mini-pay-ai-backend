#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

required=(
  deploy/k3s/overlays/server/runtime.env
  deploy/k3s/overlays/server/private/identity.env
  deploy/k3s/overlays/server/private/jwt-private.pem
  deploy/k3s/overlays/server/private/jwt-public.pem
  deploy/k3s/overlays/server/private/payment.env
  deploy/k3s/overlays/server/private/wallet.env
  deploy/k3s/overlays/server/private/commerce.env
  deploy/k3s/overlays/server/private/agent.env
  deploy/k3s/overlays/server/private/consumer-bff.env
  deploy/k3s/overlays/server/private/management-bff.env
  deploy/k3s/overlays/server/private/admin-bff.env
  deploy/k3s/overlays/server/private/yshop.env
  deploy/k3s/overlays/server/private/digests/kustomization.yaml
)
for path in "${required[@]}"; do
  [[ -s "$path" ]] || { echo "[FAIL] Missing private server file: $path" >&2; exit 1; }
done

kubectl get gatewayclass nginx >/dev/null
kubectl get crd certificates.cert-manager.io clusterissuers.cert-manager.io >/dev/null
kubectl -n minipay get secret dockerhub-pull cloudflare-api-token >/dev/null

rendered="$(kubectl kustomize deploy/k3s/overlays/server)"
if grep -Eqi 'REPLACE_|change-me|192\.168\.|host\.docker\.internal|\.minipay\.localhost|127\.0\.0\.1|localhost:' <<<"$rendered"; then
  echo "[FAIL] Server render contains placeholders or local-only values." >&2
  exit 1
fi
if grep -Eq 'image: .*:[[:space:]]*latest' <<<"$rendered"; then
  echo "[FAIL] Server render contains latest image tags." >&2
  exit 1
fi
printf '%s\n' "$rendered" | kubectl apply --dry-run=server -f - -o name >/dev/null
echo "[PASS] Server private files, digest pins, platform CRDs, pull/DNS secrets and API-server validation."
echo "[INFO] Nothing was applied. DNS and 80/443 were not changed."
