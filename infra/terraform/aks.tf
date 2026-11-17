resource "azurerm_container_registry" "main" {
  name                = "crconsignado${var.suffix}"
  resource_group_name = local.rg_name
  location            = local.location
  sku                 = "Basic"
  admin_enabled       = false
  tags                = local.tags
}

resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-consignado"
  resource_group_name = local.rg_name
  location            = local.location
  dns_prefix          = "consignado"
  sku_tier            = "Free"
  tags                = local.tags

  # Entra ID sign-in + Azure RBAC for Kubernetes; no local admin certificate.
  local_account_disabled = true

  azure_active_directory_role_based_access_control {
    tenant_id          = local.tenant_id
    azure_rbac_enabled = true
  }

  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  key_vault_secrets_provider {
    secret_rotation_enabled = false
  }

  identity {
    type = "SystemAssigned"
  }

  default_node_pool {
    name           = "system"
    vm_size        = var.aks_node_vm_size
    node_count     = 1
    vnet_subnet_id = azurerm_subnet.aks.id

    # Managed 32 GB: `az aks stop` with an ephemeral OS disk is not confirmed
    # in the Microsoft documentation (see README, "Decisions").
    os_disk_type    = "Managed"
    os_disk_size_gb = 32

    temporary_name_for_rotation = "systemtmp"
    tags                        = local.tags
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    pod_cidr            = "10.244.0.0/16"
    service_cidr        = "10.0.0.0/16"
    dns_service_ip      = "10.0.0.10"
    load_balancer_sku   = "standard"
    outbound_type       = "loadBalancer"
  }

  lifecycle {
    ignore_changes = [
      kubernetes_version,
      default_node_pool[0].orchestrator_version,
      default_node_pool[0].upgrade_settings,
    ]
  }
}
