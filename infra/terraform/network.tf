resource "azurerm_virtual_network" "main" {
  name                = "vnet-consignado"
  resource_group_name = local.rg_name
  location            = local.location
  address_space       = ["10.10.0.0/16"]
  tags                = local.tags
}

# Nodes and the internal load balancer (10.10.3.200). Pods use the overlay network.
resource "azurerm_subnet" "aks" {
  name                 = "snet-aks"
  resource_group_name  = local.rg_name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.10.0.0/22"]
}

# Outbound side of the gateway function app (Flex Consumption VNet integration).
# The service endpoint lets the API host function app accept only this subnet.
resource "azurerm_subnet" "func" {
  name                 = "snet-func"
  resource_group_name  = local.rg_name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.10.8.0/24"]
  service_endpoints    = ["Microsoft.Web"]

  # No NAT gateway (cost): the gateway reaches Entra ID, ARM, Key Vault and Storage
  # through the default outbound access of Azure.
  default_outbound_access_enabled = true

  delegation {
    name = "flex-consumption"

    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}
