#!/usr/bin/env bash
# Print the Kubernetes manifests with the ${VAR} placeholders filled in.
#
#   IMAGE=<registry>/consignado-api:<tag> ./deploy/render.sh | kubectl apply -f -
#
# Every value comes from the environment (lab.env on your machine, repository
# variables in GitHub Actions). PG_HOST is the full host name of the PostgreSQL
# server (FQDN). Nothing here is a secret: the database password stays in Key Vault.
set -euo pipefail
VARS='${IMAGE} ${APP_CLIENT_ID} ${TENANT_ID} ${KV} ${PG_HOST} ${PG_DB} ${PG_ADMIN}'
for v in IMAGE APP_CLIENT_ID TENANT_ID KV PG_HOST PG_DB PG_ADMIN; do
  [[ -n "${!v:-}" ]] || { echo "render.sh: $v is not set" >&2; exit 1; }
done
command -v envsubst >/dev/null || { echo "render.sh: envsubst not found (macOS: brew install gettext)" >&2; exit 1; }
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The namespace is a cluster-level object. The cluster admin creates it once
# (WITH_NAMESPACE=1 ./deploy/render.sh | kubectl apply -f -); the pipeline has rights
# inside the namespace only, so it leaves that file out.
for f in "$DIR"/k8s/*.yaml; do
  if [[ "$(basename "$f")" == 00-namespace.yaml && "${WITH_NAMESPACE:-0}" != "1" ]]; then continue; fi
  envsubst "$VARS" < "$f"
  echo "---"
done
