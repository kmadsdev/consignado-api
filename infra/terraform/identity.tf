# ---------- User-assigned managed identities (no client secrets anywhere) ----------

resource "azurerm_user_assigned_identity" "this" {
  for_each = {
    app    = "id-consignado-app"    # pods (workload identity)
    gw     = "id-consignado-gw"     # gateway function app
    apifn  = "id-consignado-apifn"  # API host function app
    github = "id-consignado-github" # GitHub Actions (OIDC)
  }

  name                = each.value
  resource_group_name = local.rg_name
  location            = local.location
  tags                = local.tags
}

locals {
  principal = { for k, v in azurerm_user_assigned_identity.this : k => v.principal_id }
}

# Pods: service account `consignado-api` in namespace `consignado`.
resource "azurerm_federated_identity_credential" "app" {
  name                      = "aks-consignado-api"
  user_assigned_identity_id = azurerm_user_assigned_identity.this["app"].id
  audience                  = ["api://AzureADTokenExchange"]
  issuer                    = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject                   = "system:serviceaccount:consignado:consignado-api"
}

# GitHub Actions, branch main. These repositories use the immutable subject format
# `repo:OWNER@OWNER_ID/REPO@REPO_ID:ref:refs/heads/main`.
resource "azurerm_federated_identity_credential" "github" {
  for_each = var.github_repositories

  name                      = "github-${each.key}-main"
  user_assigned_identity_id = azurerm_user_assigned_identity.this["github"].id
  audience                  = ["api://AzureADTokenExchange"]
  issuer                    = "https://token.actions.githubusercontent.com"
  subject                   = "repo:${var.github_owner.login}@${var.github_owner.id}/${each.key}@${each.value}:ref:refs/heads/main"
}

# ---------- Custom role: start and stop the cluster, nothing more ----------

resource "azurerm_role_definition" "engine_operator" {
  name        = "Consignado Engine Operator"
  scope       = azurerm_resource_group.main.id
  description = "Read, start and stop the AKS cluster of the consignado platform. No write, no delete."

  permissions {
    actions = [
      "Microsoft.ContainerService/managedClusters/read",
      "Microsoft.ContainerService/managedClusters/start/action",
      "Microsoft.ContainerService/managedClusters/stop/action",
      "Microsoft.ContainerService/managedClusters/agentPools/read",
      "Microsoft.ContainerService/managedClusters/listClusterUserCredential/action",
    ]
  }

  assignable_scopes = [azurerm_resource_group.main.id]
}

resource "azurerm_role_assignment" "engine_operator" {
  for_each = {
    gw     = local.principal["gw"]
    github = local.principal["github"]
  }

  scope              = azurerm_kubernetes_cluster.main.id
  role_definition_id = azurerm_role_definition.engine_operator.role_definition_resource_id
  principal_id       = each.value
  principal_type     = "ServicePrincipal"
}

# ---------- Built-in role assignments ----------

locals {
  aks_id = azurerm_kubernetes_cluster.main.id
  kv_id  = azurerm_key_vault.main.id
  app_st = azurerm_storage_account.app.id
  web_st = azurerm_storage_account.web.id

  role_assignments = merge(
    {
      # Signed-in user (owner / integrator)
      "user-aks-cluster-admin" = { scope = local.aks_id, role = "Azure Kubernetes Service RBAC Cluster Admin", principal = local.admin_oid, type = "User" }

      # AKS itself
      "aks-network-contributor" = { scope = azurerm_subnet.aks.id, role = "Network Contributor", principal = azurerm_kubernetes_cluster.main.identity[0].principal_id, type = "ServicePrincipal" }
      "kubelet-acr-pull"        = { scope = azurerm_container_registry.main.id, role = "AcrPull", principal = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id, type = "ServicePrincipal" }

      # Key Vault readers
      "app-kv-secrets-user"   = { scope = local.kv_id, role = "Key Vault Secrets User", principal = local.principal["app"], type = "ServicePrincipal" }
      "gw-kv-secrets-user"    = { scope = local.kv_id, role = "Key Vault Secrets User", principal = local.principal["gw"], type = "ServicePrincipal" }
      "apifn-kv-secrets-user" = { scope = local.kv_id, role = "Key Vault Secrets User", principal = local.principal["apifn"], type = "ServicePrincipal" }
      # Key Vault references of a Flex Consumption app are resolved with the system-assigned identity
      # (the provider has no `key_vault_reference_identity_id` for this resource type).
      "apifn-system-kv-secrets-user" = { scope = local.kv_id, role = "Key Vault Secrets User", principal = azurerm_function_app_flex_consumption.api.identity[0].principal_id, type = "ServicePrincipal" }

      # Gateway
      "gw-st-table-contributor" = { scope = local.app_st, role = "Storage Table Data Contributor", principal = local.principal["gw"], type = "ServicePrincipal" }
      "gw-st-blob-contributor"  = { scope = local.app_st, role = "Storage Blob Data Contributor", principal = local.principal["gw"], type = "ServicePrincipal" }
      "gw-st-blob-owner"        = { scope = local.app_st, role = "Storage Blob Data Owner", principal = local.principal["gw"], type = "ServicePrincipal" } # host storage (AzureWebJobsStorage)
      "gw-aks-rbac-reader"      = { scope = local.aks_id, role = "Azure Kubernetes Service RBAC Reader", principal = local.principal["gw"], type = "ServicePrincipal" }
      "gw-cost-reader"          = { scope = azurerm_resource_group.main.id, role = "Cost Management Reader", principal = local.principal["gw"], type = "ServicePrincipal" }

      # API host
      "apifn-st-blob-owner" = { scope = local.app_st, role = "Storage Blob Data Owner", principal = local.principal["apifn"], type = "ServicePrincipal" } # host + deployment storage

      # GitHub Actions
      "github-acr-push"         = { scope = azurerm_container_registry.main.id, role = "AcrPush", principal = local.principal["github"], type = "ServicePrincipal" }
      "github-aks-cluster-user" = { scope = local.aks_id, role = "Azure Kubernetes Service Cluster User Role", principal = local.principal["github"], type = "ServicePrincipal" }
      # RBAC Admin on one namespace only: the deploy job applies a SecretProviderClass (a custom resource),
      # which RBAC Writer cannot change. The namespace is made once by the cluster admin, not by the pipeline.
      "github-aks-ns-admin" = { scope = "${local.aks_id}/namespaces/consignado", role = "Azure Kubernetes Service RBAC Admin", principal = local.principal["github"], type = "ServicePrincipal" }
      "github-func-gw"      = { scope = azurerm_function_app_flex_consumption.gateway.id, role = "Website Contributor", principal = local.principal["github"], type = "ServicePrincipal" }
      "github-func-api"     = { scope = azurerm_function_app_flex_consumption.api.id, role = "Website Contributor", principal = local.principal["github"], type = "ServicePrincipal" }
      "github-st-app-blob"  = { scope = local.app_st, role = "Storage Blob Data Contributor", principal = local.principal["github"], type = "ServicePrincipal" }
      "github-st-web-blob"  = { scope = local.web_st, role = "Storage Blob Data Contributor", principal = local.principal["github"], type = "ServicePrincipal" }
    },
    var.enable_email ? {
      "gw-email-send" = { scope = azurerm_communication_service.main[0].id, role = "Communication and Email Service Owner", principal = local.principal["gw"], type = "ServicePrincipal" }
    } : {},
    var.enable_front_door ? {
      "github-afd-purge" = { scope = module.edge[0].profile_id, role = "CDN Endpoint Contributor", principal = local.principal["github"], type = "ServicePrincipal" }
    } : {}
  )
}

# Separate resource: the secret `db-password` waits for it (no dependency cycle with the function apps).
resource "azurerm_role_assignment" "user_kv_secrets_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = local.admin_oid
  principal_type       = "User"
}

resource "azurerm_role_assignment" "this" {
  for_each = local.role_assignments

  scope                = each.value.scope
  role_definition_name = each.value.role
  principal_id         = each.value.principal
  principal_type       = each.value.type
}

# ---------- Entra ID app registrations (no client secrets) ----------

resource "random_uuid" "scope_access_as_user" {}

resource "azuread_application" "gateway" {
  display_name     = "consignado-api-gateway"
  sign_in_audience = var.entra_sign_in_audience
  owners           = [local.admin_oid]

  api {
    requested_access_token_version = 2

    oauth2_permission_scope {
      id                         = random_uuid.scope_access_as_user.result
      value                      = "access_as_user"
      type                       = "User"
      enabled                    = true
      admin_consent_display_name = "Access the consignado platform as the signed-in user"
      admin_consent_description  = "Lets the application call the consignado API gateway for the signed-in user."
      user_consent_display_name  = "Access the consignado platform for you"
      user_consent_description   = "Lets the application call the consignado API gateway for you."
    }
  }

  lifecycle {
    # Set by azuread_application_identifier_uri (it needs the client id).
    ignore_changes = [identifier_uris]
  }
}

resource "azuread_application_identifier_uri" "gateway" {
  application_id = azuread_application.gateway.id
  identifier_uri = "api://${azuread_application.gateway.client_id}"
}

resource "azuread_service_principal" "gateway" {
  client_id = azuread_application.gateway.client_id
  owners    = [local.admin_oid]
}

resource "azuread_application" "spa" {
  display_name     = "consignado-spa"
  sign_in_audience = var.entra_sign_in_audience
  owners           = [local.admin_oid]

  single_page_application {
    redirect_uris = concat(
      [for d in values(var.custom_domains) : "https://${d}/"],
      ["http://localhost:5173/"],
    )
  }

  api {
    requested_access_token_version = 2
  }

  required_resource_access {
    resource_app_id = azuread_application.gateway.client_id

    resource_access {
      id   = random_uuid.scope_access_as_user.result
      type = "Scope"
    }
  }
}

resource "azuread_service_principal" "spa" {
  client_id = azuread_application.spa.client_id
  owners    = [local.admin_oid]
}

# The SPA is a known client of the gateway: no consent prompt for `access_as_user`.
resource "azuread_application_pre_authorized" "spa" {
  application_id       = azuread_application.gateway.id
  authorized_client_id = azuread_application.spa.client_id
  permission_ids       = [random_uuid.scope_access_as_user.result]
}
