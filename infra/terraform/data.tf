# ---------- PostgreSQL ----------

resource "random_password" "db" {
  length  = 28
  special = false
}

resource "azurerm_postgresql_flexible_server" "main" {
  name                = var.pg_server_name
  resource_group_name = local.rg_name
  location            = var.pg_location
  version             = "16"
  sku_name            = "B_Standard_B1ms"
  storage_mb          = 32768
  auto_grow_enabled   = false

  administrator_login    = local.db_username
  administrator_password = random_password.db.result

  backup_retention_days        = 7
  geo_redundant_backup_enabled = false

  # Owner's decision (cost): public endpoint, TLS required (server default
  # require_secure_transport = on), only the "Azure services" firewall rule.
  public_network_access_enabled = true

  authentication {
    password_auth_enabled         = true
    active_directory_auth_enabled = false
  }

  tags = local.tags

  lifecycle {
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "consignado" {
  name      = local.db_name
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# 0.0.0.0 - 0.0.0.0 = "Allow public access from any Azure service within Azure".
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  name             = "AllowAllAzureServices"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# ---------- Key Vault ----------

resource "azurerm_key_vault" "main" {
  name                       = "kv-consig-${var.suffix}"
  resource_group_name        = local.rg_name
  location                   = local.location
  tenant_id                  = local.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  soft_delete_retention_days = 7
  purge_protection_enabled   = false
  tags                       = local.tags
}

# The secrets `apifn-host-key` and `turnstile-secret` are set by the integrator.
resource "azurerm_key_vault_secret" "db_password" {
  name         = "db-password"
  key_vault_id = azurerm_key_vault.main.id
  value        = random_password.db.result
  content_type = "PostgreSQL password of ${local.db_username}"
  tags         = local.tags

  depends_on = [azurerm_role_assignment.user_kv_secrets_officer]
}

# ---------- Storage: static site ----------

resource "azurerm_storage_account" "web" {
  name                            = "stconsigweb${var.suffix}"
  resource_group_name             = local.rg_name
  location                        = local.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  account_kind                    = "StorageV2"
  min_tls_version                 = "TLS1_2"
  https_traffic_only_enabled      = true
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  tags                            = local.tags
}

resource "azurerm_storage_account_static_website" "web" {
  storage_account_id = azurerm_storage_account.web.id
  index_document     = "index.html"
  error_404_document = "index.html"
}

# ---------- Storage: application ----------

resource "azurerm_storage_account" "app" {
  name                            = "stconsigapp${var.suffix}"
  resource_group_name             = local.rg_name
  location                        = local.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  account_kind                    = "StorageV2"
  min_tls_version                 = "TLS1_2"
  https_traffic_only_enabled      = true
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false
  tags                            = local.tags
}

resource "azurerm_storage_container" "app" {
  for_each = toset(["deploy-gateway", "deploy-api", "data"])

  name                  = each.key
  storage_account_id    = azurerm_storage_account.app.id
  container_access_type = "private"
}

# Contract section 5. The tables are made with an ARM deployment: `azurerm_storage_table` sets the table
# ACL with the account key, and this account has no key access ("Key based authentication is not permitted").
locals {
  tables = ["users", "sessions", "engine", "contacts", "audit"]
}

resource "azurerm_resource_group_template_deployment" "tables" {
  name                = "consignado-tables"
  resource_group_name = local.rg_name
  deployment_mode     = "Incremental"
  tags                = local.tags

  template_content = jsonencode({
    "$schema"      = "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#"
    contentVersion = "1.0.0.0"
    resources = [
      for t in local.tables : {
        type       = "Microsoft.Storage/storageAccounts/tableServices/tables"
        apiVersion = "2023-05-01"
        name       = "${azurerm_storage_account.app.name}/default/${t}"
      }
    ]
  })
}
