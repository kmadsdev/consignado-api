#!/usr/bin/env bash
# Print the Kubernetes manifests with the ${VAR} placeholders filled in.
#
#   IMAGE=<registry>/consignado-api:<tag> ./deploy/render.sh | kubectl apply -f -
#
# Every value comes from the environment (lab.env on your machine, repository
# variables in GitHub Actions). Nothing here is a secret: the database password
# stays in Key Vault.
set -euo pipefail
VARS='${IMAGE} ${APP_CLIENT_ID} ${TENANT_ID} ${KV} ${PG} ${PG_DB} ${PG_ADMIN} ${ALLOWED_CIDR}'
for v in IMAGE APP_CLIENT_ID TENANT_ID KV PG PG_DB PG_ADMIN ALLOWED_CIDR; do
  [[ -n "${!v:-}" ]] || { echo "render.sh: $v is not set" >&2; exit 1; }
done
command -v envsubst >/dev/null || { echo "render.sh: envsubst not found (macOS: brew install gettext)" >&2; exit 1; }
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for f in "$DIR"/k8s/*.yaml; do
  envsubst "$VARS" < "$f"
  echo "---"
done
