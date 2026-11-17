# Front Door Standard + WAF. One module with a switch, because the offer "Azure for Students"
# refuses the whole resource type (2026-11-17):
#   "BadRequest: Free Trial and Student account is forbidden for Azure Frontdoor resources."
# Set enable_front_door = true on a subscription that allows it; nothing else changes.
module "edge" {
  source = "./modules/edge"
  count  = var.enable_front_door ? 1 : 0

  resource_group_name = local.rg_name
  suffix              = var.suffix
  tags                = local.tags
  web_origin_host     = azurerm_storage_account.web.primary_web_host
  # Literal host name (not the resource attribute): the gateway needs the Front Door id, so the attribute makes a cycle.
  gateway_origin_host       = "func-consignado-gw-${var.suffix}.azurewebsites.net"
  custom_domains            = var.custom_domains
  rate_limit_api_per_minute = var.rate_limit_api_per_minute
  rate_limit_all_per_minute = var.rate_limit_all_per_minute
}

locals {
  front_door_id = one(module.edge[*].fdid)
}
