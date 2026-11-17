# Flex Consumption: one plan per app (a Flex Consumption plan holds one app).
resource "azurerm_service_plan" "gateway" {
  name                = "asp-consignado-gw-${var.suffix}"
  resource_group_name = local.rg_name
  location            = local.location
  os_type             = "Linux"
  sku_name            = "FC1"
  tags                = local.tags
}

resource "azurerm_service_plan" "api" {
  name                = "asp-consignado-api-${var.suffix}"
  resource_group_name = local.rg_name
  location            = local.location
  os_type             = "Linux"
  sku_name            = "FC1"
  tags                = local.tags
}

locals {
  email_settings = var.enable_email ? {
    ACS_ENDPOINT      = "https://${azurerm_communication_service.main[0].hostname}"
    EMAIL_SENDER      = "DoNotReply@${azurerm_email_communication_service_domain.azure_managed[0].from_sender_domain}"
    EMAIL_RESOURCE_ID = azurerm_communication_service.main[0].id
  } : {}
}

# ---------- Gateway (Java 21) ----------

resource "azurerm_function_app_flex_consumption" "gateway" {
  name                = "func-consignado-gw-${var.suffix}"
  resource_group_name = local.rg_name
  location            = local.location
  service_plan_id     = azurerm_service_plan.gateway.id
  tags                = local.tags

  runtime_name           = "java"
  runtime_version        = "21"
  instance_memory_in_mb  = 2048
  maximum_instance_count = var.function_max_instances
  https_only             = true

  # Deployment package: blob container, read with the app's identity (no connection string).
  storage_container_type            = "blobContainer"
  storage_container_endpoint        = "${azurerm_storage_account.app.primary_blob_endpoint}${azurerm_storage_container.app["deploy-gateway"].name}"
  storage_authentication_type       = "UserAssignedIdentity"
  storage_user_assigned_identity_id = azurerm_user_assigned_identity.this["gw"].id

  virtual_network_subnet_id = var.enable_function_vnet_integration ? azurerm_subnet.func.id : null

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.this["gw"].id]
  }

  site_config {
    minimum_tls_version                    = "1.2"
    application_insights_connection_string = azurerm_application_insights.main.connection_string

    # Inbound: only Azure Front Door, and only this Front Door profile.
    # Without Front Door (refused on this subscription): only var.gateway_allowed_cidrs (the Cloudflare ranges),
    # or all sources when that list is empty. The gateway also checks a shared header (Key Vault secret `edge-key`).
    ip_restriction_default_action = var.enable_front_door || length(var.gateway_allowed_cidrs) > 0 ? "Deny" : "Allow"

    dynamic "ip_restriction" {
      for_each = var.enable_front_door ? [local.front_door_id] : []

      content {
        name        = "front-door-only"
        priority    = 100
        action      = "Allow"
        service_tag = "AzureFrontDoor.Backend"

        headers = [{
          x_azure_fdid      = [ip_restriction.value]
          x_fd_health_probe = []
          x_forwarded_for   = []
          x_forwarded_host  = []
        }]
      }
    }

    dynamic "ip_restriction" {
      for_each = var.enable_front_door ? [] : var.gateway_allowed_cidrs

      content {
        name       = "edge-${ip_restriction.key + 1}"
        priority   = 200 + ip_restriction.key
        action     = "Allow"
        ip_address = ip_restriction.value
      }
    }
  }

  app_settings = merge({
    # Host storage with the identity (no connection string).
    AzureWebJobsStorage__accountName = azurerm_storage_account.app.name
    AzureWebJobsStorage__credential  = "managedidentity"
    AzureWebJobsStorage__clientId    = azurerm_user_assigned_identity.this["gw"].client_id

    AZURE_CLIENT_ID     = azurerm_user_assigned_identity.this["gw"].client_id
    ENTRA_TENANT_ID     = local.tenant_id
    ENTRA_API_AUDIENCE  = azuread_application.gateway.client_id
    ADMIN_OID           = local.admin_oid
    AKS_RESOURCE_ID     = azurerm_kubernetes_cluster.main.id
    AKS_API_SERVER_URL  = "https://${azurerm_kubernetes_cluster.main.fqdn}:443"
    AKS_CA_CERT_B64     = azurerm_kubernetes_cluster.main.kube_config[0].cluster_ca_certificate
    AKS_INTERNAL_URL    = "http://${var.aks_internal_lb_ip}"
    APIFN_BASE_URL      = "https://${azurerm_function_app_flex_consumption.api.default_hostname}"
    KEY_VAULT_URL       = azurerm_key_vault.main.vault_uri
    STORAGE_ACCOUNT     = azurerm_storage_account.app.name
    EDGE                = var.edge
    OWNER_EMAIL         = var.owner_email
    WINDOW_MINUTES      = "15"
    WINDOWS_MAX         = "3"
    COOLDOWN_MINUTES    = "15"
    BUDGET_LIMIT_USD    = tostring(var.budget_amount)
    AZURE_SUBSCRIPTION  = var.subscription_id
    AZURE_RESOURCEGROUP = local.rg_name
  }, local.email_settings)
}

# ---------- API host (custom handler: the Spring Boot jar) ----------

resource "azurerm_function_app_flex_consumption" "api" {
  name                = "func-consignado-api-${var.suffix}"
  resource_group_name = local.rg_name
  location            = local.location
  service_plan_id     = azurerm_service_plan.api.id
  tags                = local.tags

  runtime_name           = "custom"
  runtime_version        = "1.0"
  instance_memory_in_mb  = 2048
  maximum_instance_count = var.function_max_instances
  https_only             = true

  storage_container_type            = "blobContainer"
  storage_container_endpoint        = "${azurerm_storage_account.app.primary_blob_endpoint}${azurerm_storage_container.app["deploy-api"].name}"
  storage_authentication_type       = "UserAssignedIdentity"
  storage_user_assigned_identity_id = azurerm_user_assigned_identity.this["apifn"].id

  # System-assigned too: Key Vault references are resolved with it.
  identity {
    type         = "SystemAssigned, UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.this["apifn"].id]
  }

  site_config {
    minimum_tls_version                    = "1.2"
    application_insights_connection_string = azurerm_application_insights.main.connection_string

    # Inbound: only the gateway's subnet (service endpoint Microsoft.Web on snet-func).
    ip_restriction_default_action = "Deny"

    ip_restriction {
      name                      = "snet-func-only"
      priority                  = 100
      action                    = "Allow"
      virtual_network_subnet_id = azurerm_subnet.func.id
    }
  }

  app_settings = {
    AzureWebJobsStorage__accountName = azurerm_storage_account.app.name
    AzureWebJobsStorage__credential  = "managedidentity"
    AzureWebJobsStorage__clientId    = azurerm_user_assigned_identity.this["apifn"].client_id

    AZURE_CLIENT_ID = azurerm_user_assigned_identity.this["apifn"].client_id
    DB_URL          = "jdbc:postgresql://${azurerm_postgresql_flexible_server.main.fqdn}:5432/${local.db_name}?sslmode=require"
    DB_USERNAME     = local.db_username
    DB_PASSWORD     = "@Microsoft.KeyVault(SecretUri=${azurerm_key_vault_secret.db_password.versionless_id})"
  }
}
