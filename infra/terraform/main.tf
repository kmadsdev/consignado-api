data "azurerm_client_config" "current" {}

data "azurerm_subscription" "current" {}

locals {
  tags = {
    project    = "consignado"
    managed-by = "terraform"
  }

  tenant_id   = data.azurerm_client_config.current.tenant_id
  admin_oid   = data.azurerm_client_config.current.object_id
  rg_name     = azurerm_resource_group.main.name
  location    = azurerm_resource_group.main.location
  db_name     = "consignado"
  db_username = "pgadmin"
}

resource "azurerm_resource_group" "main" {
  name     = "rg-consignado"
  location = var.location
  tags     = local.tags
}
