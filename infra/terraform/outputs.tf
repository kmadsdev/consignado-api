# Non-secret values only. `terraform output -json > outputs.json` is committed.

output "tenant_id" { value = local.tenant_id }
output "subscription_id" { value = var.subscription_id }
output "resource_group" { value = local.rg_name }
output "location" { value = local.location }
output "admin_oid" { value = local.admin_oid }

output "vnet" {
  value = {
    name         = azurerm_virtual_network.main.name
    id           = azurerm_virtual_network.main.id
    snet_aks_id  = azurerm_subnet.aks.id
    snet_func_id = azurerm_subnet.func.id
  }
}

output "aks" {
  value = {
    name                = azurerm_kubernetes_cluster.main.name
    id                  = azurerm_kubernetes_cluster.main.id
    api_server_url      = "https://${azurerm_kubernetes_cluster.main.fqdn}:443"
    oidc_issuer_url     = azurerm_kubernetes_cluster.main.oidc_issuer_url
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
    id           = azurerm_container_registry.main.id
    login_server = azurerm_container_registry.main.login_server
  }
}

output "key_vault" {
  value = {
    name         = azurerm_key_vault.main.name
    id           = azurerm_key_vault.main.id
    uri          = azurerm_key_vault.main.vault_uri
    secret_names = ["db-password", "edge-key", "hash-key", "apifn-host-key", "turnstile-secret"]
  }
}

output "postgres" {
  value = {
    server_name     = azurerm_postgresql_flexible_server.main.name
    id              = azurerm_postgresql_flexible_server.main.id
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
      id           = azurerm_storage_account.web.id
      web_host     = azurerm_storage_account.web.primary_web_host
      web_endpoint = azurerm_storage_account.web.primary_web_endpoint
      container    = "$web"
    }
    app = {
      name           = azurerm_storage_account.app.name
      id             = azurerm_storage_account.app.id
      blob_endpoint  = azurerm_storage_account.app.primary_blob_endpoint
      table_endpoint = azurerm_storage_account.app.primary_table_endpoint
      containers     = sort([for c in azurerm_storage_container.app : c.name])
      tables         = sort(local.tables)
    }
  }
}

output "monitoring" {
  value = {
    log_analytics_name    = azurerm_log_analytics_workspace.main.name
    log_analytics_id      = azurerm_log_analytics_workspace.main.id
    app_insights_name     = azurerm_application_insights.main.name
    app_insights_id       = azurerm_application_insights.main.id
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
      id               = azurerm_function_app_flex_consumption.gateway.id
      hostname         = azurerm_function_app_flex_consumption.gateway.default_hostname
      deploy_container = "deploy-gateway"
      vnet_integrated  = var.enable_function_vnet_integration
      base_url         = "https://${azurerm_function_app_flex_consumption.gateway.default_hostname}"
      inbound          = var.enable_front_door ? "front-door-only" : (length(var.gateway_allowed_cidrs) > 0 ? "edge-ip-ranges-only" : "all-sources")
      allowed_cidrs    = var.enable_front_door ? [] : var.gateway_allowed_cidrs
    }
    api = {
      name             = azurerm_function_app_flex_consumption.api.name
      id               = azurerm_function_app_flex_consumption.api.id
      hostname         = azurerm_function_app_flex_consumption.api.default_hostname
      base_url         = "https://${azurerm_function_app_flex_consumption.api.default_hostname}"
      deploy_container = "deploy-api"
    }
  }
}

output "identities" {
  value = {
    for k, v in azurerm_user_assigned_identity.this : v.name => {
      id           = v.id
      client_id    = v.client_id
      principal_id = v.principal_id
    }
  }
}

output "github_oidc" {
  value = {
    client_id       = azurerm_user_assigned_identity.this["github"].client_id
    tenant_id       = local.tenant_id
    subscription_id = var.subscription_id
    subjects        = { for k, v in azurerm_federated_identity_credential.github : k => v.subject }
  }
}

output "entra" {
  value = {
    tenant_id        = local.tenant_id
    authority        = "https://login.microsoftonline.com/${local.tenant_id}"
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
