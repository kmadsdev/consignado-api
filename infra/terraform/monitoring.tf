resource "azurerm_log_analytics_workspace" "main" {
  name                = "log-consignado"
  resource_group_name = local.rg_name
  location            = local.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
  daily_quota_gb      = 1 # cost guard: ingestion stops for the day after 1 GB
  tags                = local.tags
}

resource "azurerm_application_insights" "main" {
  name                = "appi-consignado"
  resource_group_name = local.rg_name
  location            = local.location
  workspace_id        = azurerm_log_analytics_workspace.main.id
  application_type    = "web"
  tags                = local.tags
}

# ---------- Email (Azure Communication Services) ----------

resource "azurerm_communication_service" "main" {
  count = var.enable_email ? 1 : 0

  name                = "acs-consignado-${var.suffix}"
  resource_group_name = local.rg_name
  data_location       = var.email_data_location
  tags                = local.tags
}

resource "azurerm_email_communication_service" "main" {
  count = var.enable_email ? 1 : 0

  name                = "acs-email-consignado-${var.suffix}"
  resource_group_name = local.rg_name
  data_location       = var.email_data_location
  tags                = local.tags
}

resource "azurerm_email_communication_service_domain" "azure_managed" {
  count = var.enable_email ? 1 : 0

  name              = "AzureManagedDomain"
  email_service_id  = azurerm_email_communication_service.main[0].id
  domain_management = "AzureManaged"
  tags              = local.tags
}

resource "azurerm_communication_service_email_domain_association" "main" {
  count = var.enable_email ? 1 : 0

  communication_service_id = azurerm_communication_service.main[0].id
  email_service_domain_id  = azurerm_email_communication_service_domain.azure_managed[0].id
}
