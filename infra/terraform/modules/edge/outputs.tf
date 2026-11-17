output "profile_id" {
  value = azurerm_cdn_frontdoor_profile.main.id
}

output "summary" {
  value = {
    profile_name  = azurerm_cdn_frontdoor_profile.main.name
    profile_id    = azurerm_cdn_frontdoor_profile.main.id
    fdid          = azurerm_cdn_frontdoor_profile.main.resource_guid
    endpoint_name = azurerm_cdn_frontdoor_endpoint.main.name
    endpoint_host = azurerm_cdn_frontdoor_endpoint.main.host_name
    waf_policy    = azurerm_cdn_frontdoor_firewall_policy.main.name
    domains       = [for d in azurerm_cdn_frontdoor_custom_domain.this : d.host_name]
  }
}

# Records to create in Cloudflare (DNS only, not proxied).
output "dns_records" {
  value = flatten([
    for k, d in azurerm_cdn_frontdoor_custom_domain.this : [
      {
        type    = "TXT"
        name    = "_dnsauth.${d.host_name}"
        value   = d.validation_token
        purpose = "Azure-managed certificate validation"
      },
      {
        type    = "CNAME"
        name    = d.host_name
        value   = azurerm_cdn_frontdoor_endpoint.main.host_name
        purpose = "traffic to Front Door (Cloudflare proxy off)"
      },
    ]
  ])
}

# Separate output: the gateway function app needs only this value (no dependency cycle with the origins).
output "fdid" {
  value = azurerm_cdn_frontdoor_profile.main.resource_guid
}
