#!/usr/bin/env bash
# Creates the remote Terraform state (run one time, before `terraform init`).
# Needs: Azure CLI, signed in as Owner of the subscription. No account key is used.
set -euo pipefail

RG="${TFSTATE_RG:-rg-consignado-tfstate}"
SA="${TFSTATE_SA:-stconsigtfkm01}"
CONTAINER="${TFSTATE_CONTAINER:-tfstate}"
LOCATION="${TFSTATE_LOCATION:-belgiumcentral}"
TAGS=(project=consignado managed-by=bootstrap)

az group create --name "$RG" --location "$LOCATION" --tags "${TAGS[@]}" --output none

az storage account create \
  --name "$SA" --resource-group "$RG" --location "$LOCATION" \
  --sku Standard_LRS --kind StorageV2 \
  --min-tls-version TLS1_2 --https-only true \
  --allow-blob-public-access false \
  --allow-shared-key-access false \
  --tags "${TAGS[@]}" --output none

az storage account blob-service-properties update \
  --account-name "$SA" --resource-group "$RG" \
  --enable-versioning true \
  --enable-delete-retention true --delete-retention-days 14 \
  --output none

SA_ID="$(az storage account show --name "$SA" --resource-group "$RG" --query id --output tsv)"
USER_OID="$(az ad signed-in-user show --query id --output tsv)"

# Idempotent: the command returns the existing assignment when it is there.
az role assignment create \
  --assignee-object-id "$USER_OID" --assignee-principal-type User \
  --role "Storage Blob Data Contributor" --scope "$SA_ID" --output none

# The data-plane role needs some time to propagate.
for attempt in $(seq 1 20); do
  if az storage container create --name "$CONTAINER" --account-name "$SA" \
      --auth-mode login --output none 2>/dev/null; then
    echo "state backend ready: $SA/$CONTAINER in $RG"
    exit 0
  fi
  echo "waiting for the role assignment to propagate ($attempt/20)"
  sleep 15
done

echo "the container was not created; run the script again in some minutes" >&2
exit 1
