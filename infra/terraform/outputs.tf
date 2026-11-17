# Non-secret values only. `terraform output -json > outputs.json` is committed to a PUBLIC repository.
# Security review I4 (2026-11-17): no tenant id, no subscription id, no object id of a person. ARM resource ids hold
# the subscription id, so no output has one; the names are enough (`az resource show -g rg-consignado -n <name> ...`).
# Read the ids with `az account show` or `az <type> show --query id` when you need them.

output "resource_group" { value = local.rg_name }
output "location" { value = local.location }

output "vnet" {
  value = {
    name = azurerm_virtual_network.main.name
  }
}

output "aks" {
  value = {
    name                = azurerm_kubernetes_cluster.main.name
    api_server_url      = "https://${azurerm_kubernetes_cluster.main.fqdn}:443"
    node_resource_group = azurerm_kubernetes_cluster.main.node_resource_group
    internal_lb_ip      = var.aks_internal_lb_ip
    internal_url        = "http://${var.aks_internal_lb_ip}"
    namespace           = "consignado"
    service_account     = "consignado-api"
    kubelet_client_id   = azurerm_kubernetes_cluster.main.kubelet_identity[0].client_id
    csi_client_id       = azurerm_kubernetes_cluster.main.key_vault_secrets_provider[0].secret_identity[0].client_id
    engine_role_name    = azurerm_role_definition.engine_operator.name
  }
}

output "registry" {
  value = {
    name         = azurerm_container_registry.main.name
    login_server = azurerm_container_registry.main.login_server
  }
}

output "key_vault" {
  value = {
    name             = azurerm_key_vault.main.name
    uri              = azurerm_key_vault.main.vault_uri
    secret_names     = ["db-password", "edge-key", "hash-key", "apifn-host-key", "turnstile-secret"]
    purge_protection = azurerm_key_vault.main.purge_protection_enabled
  }
}

output "postgres" {
  value = {
    server_name     = azurerm_postgresql_flexible_server.main.name
    location        = azurerm_postgresql_flexible_server.main.location
    fqdn            = azurerm_postgresql_flexible_server.main.fqdn
    database        = local.db_name
    username        = local.db_username
    jdbc_url        = "jdbc:postgresql://${azurerm_postgresql_flexible_server.main.fqdn}:5432/${local.db_name}?sslmode=require"
    password_secret = "db-password"
  }
}

output "storage" {
  value = {
    web = {
      name         = azurerm_storage_account.web.name
      web_host     = azurerm_storage_account.web.primary_web_host
      web_endpoint = azurerm_storage_account.web.primary_web_endpoint
      container    = "$web"
    }
    app = {
      name           = azurerm_storage_account.app.name
      blob_endpoint  = azurerm_storage_account.app.primary_blob_endpoint
      table_endpoint = azurerm_storage_account.app.primary_table_endpoint
      containers     = sort([for c in azurerm_storage_container.app : c.name])
      tables         = sort(local.tables)
    }
    apihost = {
      name          = azurerm_storage_account.apihost.name
      blob_endpoint = azurerm_storage_account.apihost.primary_blob_endpoint
      containers    = [azurerm_storage_container.apihost_deploy.name]
    }
  }
}

output "monitoring" {
  value = {
    log_analytics_name    = azurerm_log_analytics_workspace.main.name
    app_insights_name     = azurerm_application_insights.main.name
    app_insights_app_id   = azurerm_application_insights.main.app_id
    budget_action_group   = one(azurerm_monitor_action_group.budget[*].name)
    budget_name           = one(azurerm_consumption_budget_subscription.main[*].name)
    budget_webhook_is_set = var.budget_webhook_url != ""
  }
}

output "functions" {
  value = {
    gateway = {
      name             = azurerm_function_app_flex_consumption.gateway.name
      hostname         = azurerm_function_app_flex_consumption.gateway.default_hostname
      deploy_container = "deploy-gateway"
      storage_account  = azurerm_storage_account.app.name
      max_instances    = var.gateway_max_instances
      vnet_integrated  = var.enable_function_vnet_integration
      base_url         = "https://${azurerm_function_app_flex_consumption.gateway.default_hostname}"
      inbound          = var.enable_front_door ? "front-door-only" : (length(var.gateway_allowed_cidrs) > 0 ? "edge-ip-ranges-only" : "all-sources")
      allowed_cidrs    = var.enable_front_door ? [] : var.gateway_allowed_cidrs
    }
    api = {
      name             = azurerm_function_app_flex_consumption.api.name
      hostname         = azurerm_function_app_flex_consumption.api.default_hostname
      base_url         = "https://${azurerm_function_app_flex_consumption.api.default_hostname}"
      deploy_container = azurerm_storage_container.apihost_deploy.name
      storage_account  = azurerm_storage_account.apihost.name
      max_instances    = var.api_host_max_instances
    }
  }
}

output "identities" {
  value = {
    for k, v in azurerm_user_assigned_identity.this : v.name => {
      client_id    = v.client_id
      principal_id = v.principal_id
    }
  }
}

output "github_oidc" {
  value = {
    client_id = azurerm_user_assigned_identity.this["github"].client_id
    subjects  = { for k, v in azurerm_federated_identity_credential.github : k => v.subject }
  }
}

output "entra" {
  value = {
    sign_in_audience = var.entra_sign_in_audience
    spa = {
      display_name  = azuread_application.spa.display_name
      client_id     = azuread_application.spa.client_id
      object_id     = azuread_application.spa.object_id
      redirect_uris = sort(tolist(azuread_application.spa.single_page_application[0].redirect_uris))
    }
    gateway = {
      display_name   = azuread_application.gateway.display_name
      client_id      = azuread_application.gateway.client_id
      object_id      = azuread_application.gateway.object_id
      identifier_uri = azuread_application_identifier_uri.gateway.identifier_uri
      audience       = azuread_application.gateway.client_id
      scope          = "${azuread_application_identifier_uri.gateway.identifier_uri}/access_as_user"
    }
  }
}

# null while enable_front_door = false (refused on this subscription).
output "front_door" {
  value = one(module.edge[*].summary)
}

# Records to create in Cloudflare (DNS only, not proxied). Empty while enable_front_door = false.
output "dns_records" {
  value = flatten(module.edge[*].dns_records)
}

output "edge" {
  value = {
    kind                 = var.enable_front_door ? "front-door" : var.edge
    static_site_host     = azurerm_storage_account.web.primary_web_host
    gateway_host         = azurerm_function_app_flex_consumption.gateway.default_hostname
    shared_header_secret = "edge-key"
  }
}

output "email" {
  value = var.enable_email ? {
    communication_service = azurerm_communication_service.main[0].name
    endpoint              = "https://${azurerm_communication_service.main[0].hostname}"
    sender                = "DoNotReply@${azurerm_email_communication_service_domain.azure_managed[0].from_sender_domain}"
    data_location         = var.email_data_location
  } : null
}
