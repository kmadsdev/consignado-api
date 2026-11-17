resource "azurerm_cdn_frontdoor_profile" "main" {
  name                     = "afd-consignado"
  resource_group_name      = var.resource_group_name
  sku_name                 = "Standard_AzureFrontDoor"
  response_timeout_seconds = 120
  tags                     = var.tags
}

resource "azurerm_cdn_frontdoor_endpoint" "main" {
  name                     = "consignado-${var.suffix}"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id
  tags                     = var.tags
}

# ---------- Origins ----------

resource "azurerm_cdn_frontdoor_origin_group" "web" {
  name                     = "og-web"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id

  load_balancing {}
}

resource "azurerm_cdn_frontdoor_origin" "web" {
  name                           = "static-site"
  cdn_frontdoor_origin_group_id  = azurerm_cdn_frontdoor_origin_group.web.id
  enabled                        = true
  host_name                      = var.web_origin_host
  origin_host_header             = var.web_origin_host
  http_port                      = 80
  https_port                     = 443
  certificate_name_check_enabled = true
}

# No health probe: one origin only, and a probe would keep the function app awake (cost).
resource "azurerm_cdn_frontdoor_origin_group" "gateway" {
  name                     = "og-gateway"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id

  load_balancing {}
}

resource "azurerm_cdn_frontdoor_origin" "gateway" {
  name                           = "gateway"
  cdn_frontdoor_origin_group_id  = azurerm_cdn_frontdoor_origin_group.gateway.id
  enabled                        = true
  host_name                      = var.gateway_origin_host
  origin_host_header             = var.gateway_origin_host
  http_port                      = 80
  https_port                     = 443
  certificate_name_check_enabled = true
}

# ---------- Custom domains (Azure-managed certificates; DNS is on Cloudflare) ----------

resource "azurerm_cdn_frontdoor_custom_domain" "this" {
  for_each = var.custom_domains

  name                     = each.key
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id
  host_name                = each.value

  tls {
    certificate_type = "ManagedCertificate"
  }
}

# ---------- Rule sets ----------

resource "azurerm_cdn_frontdoor_rule_set" "security_headers" {
  name                     = "securityheaders"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id
}

resource "azurerm_cdn_frontdoor_rule" "security_headers" {
  name                      = "securityheaders"
  cdn_frontdoor_rule_set_id = azurerm_cdn_frontdoor_rule_set.security_headers.id
  order                     = 1
  behavior_on_match         = "Continue"

  actions {
    response_header_action {
      header_action = "Overwrite"
      header_name   = "Strict-Transport-Security"
      value         = "max-age=31536000; includeSubDomains"
    }
    response_header_action {
      header_action = "Overwrite"
      header_name   = "X-Content-Type-Options"
      value         = "nosniff"
    }
    response_header_action {
      header_action = "Overwrite"
      header_name   = "Referrer-Policy"
      value         = "strict-origin-when-cross-origin"
    }
    response_header_action {
      header_action = "Overwrite"
      header_name   = "X-Frame-Options"
      value         = "DENY"
    }
  }

  depends_on = [azurerm_cdn_frontdoor_origin.web, azurerm_cdn_frontdoor_origin.gateway]
}

resource "azurerm_cdn_frontdoor_rule_set" "spa" {
  name                     = "sparewrite"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id
}

# A path with no file extension is a client-side route: serve /index.html (status 200).
resource "azurerm_cdn_frontdoor_rule" "spa" {
  name                      = "spafallback"
  cdn_frontdoor_rule_set_id = azurerm_cdn_frontdoor_rule_set.spa.id
  order                     = 1
  behavior_on_match         = "Continue"

  conditions {
    # "file extension length is not greater than 0" = no file extension
    url_file_extension_condition {
      operator         = "GreaterThan"
      negate_condition = true
      match_values     = ["0"]
    }
    url_path_condition {
      operator         = "BeginsWith"
      negate_condition = true
      match_values     = ["api/"]
      transforms       = ["Lowercase"]
    }
  }

  actions {
    url_rewrite_action {
      source_pattern          = "/"
      destination             = "/index.html"
      preserve_unmatched_path = false
    }
  }

  depends_on = [azurerm_cdn_frontdoor_origin.web, azurerm_cdn_frontdoor_origin.gateway]
}

# ---------- Routes ----------

resource "azurerm_cdn_frontdoor_route" "api" {
  name                          = "route-api"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.main.id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.gateway.id
  cdn_frontdoor_origin_ids      = [azurerm_cdn_frontdoor_origin.gateway.id]
  cdn_frontdoor_rule_set_ids    = [azurerm_cdn_frontdoor_rule_set.security_headers.id]
  enabled                       = true

  patterns_to_match      = ["/api/*"]
  supported_protocols    = ["Http", "Https"]
  https_redirect_enabled = true
  forwarding_protocol    = "HttpsOnly"
  link_to_default_domain = true

  cdn_frontdoor_custom_domain_ids = [for d in azurerm_cdn_frontdoor_custom_domain.this : d.id]

  # No `cache` block: API responses are never cached.
}

resource "azurerm_cdn_frontdoor_route" "web" {
  name                          = "route-web"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.main.id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.web.id
  cdn_frontdoor_origin_ids      = [azurerm_cdn_frontdoor_origin.web.id]
  cdn_frontdoor_rule_set_ids = [
    azurerm_cdn_frontdoor_rule_set.spa.id,
    azurerm_cdn_frontdoor_rule_set.security_headers.id,
  ]
  enabled = true

  patterns_to_match      = ["/*"]
  supported_protocols    = ["Http", "Https"]
  https_redirect_enabled = true
  forwarding_protocol    = "HttpsOnly"
  link_to_default_domain = true

  cdn_frontdoor_custom_domain_ids = [for d in azurerm_cdn_frontdoor_custom_domain.this : d.id]

  cache {
    query_string_caching_behavior = "IgnoreQueryString"
    compression_enabled           = true
    content_types_to_compress = [
      "text/html", "text/css", "text/plain", "text/javascript", "application/javascript",
      "application/json", "application/xml", "image/svg+xml",
    ]
  }
}

resource "azurerm_cdn_frontdoor_custom_domain_association" "this" {
  for_each = azurerm_cdn_frontdoor_custom_domain.this

  cdn_frontdoor_custom_domain_id = each.value.id
  cdn_frontdoor_route_ids = [
    azurerm_cdn_frontdoor_route.api.id,
    azurerm_cdn_frontdoor_route.web.id,
  ]
}

# ---------- WAF (Standard tier: custom rules only) ----------

resource "azurerm_cdn_frontdoor_firewall_policy" "main" {
  name                = "wafconsignado"
  resource_group_name = var.resource_group_name
  sku_name            = azurerm_cdn_frontdoor_profile.main.sku_name
  enabled             = true
  mode                = "Prevention"
  tags                = var.tags

  custom_block_response_status_code = 429

  # Counted per client IP (the default grouping of a rate-limit rule).
  custom_rule {
    name                           = "RateLimitApi"
    enabled                        = true
    priority                       = 100
    type                           = "RateLimitRule"
    action                         = "Block"
    rate_limit_duration_in_minutes = 1
    rate_limit_threshold           = var.rate_limit_api_per_minute

    match_condition {
      match_variable = "RequestUri"
      operator       = "Contains"
      match_values   = ["/api/"]
      transforms     = ["Lowercase"]
    }
  }

  custom_rule {
    name                           = "RateLimitAll"
    enabled                        = true
    priority                       = 200
    type                           = "RateLimitRule"
    action                         = "Block"
    rate_limit_duration_in_minutes = 1
    rate_limit_threshold           = var.rate_limit_all_per_minute

    match_condition {
      match_variable = "SocketAddr"
      operator       = "IPMatch"
      match_values   = ["0.0.0.0/0", "::/0"]
    }
  }
}

resource "azurerm_cdn_frontdoor_security_policy" "waf" {
  name                     = "waf-consignado"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id

  security_policies {
    firewall {
      cdn_frontdoor_firewall_policy_id = azurerm_cdn_frontdoor_firewall_policy.main.id

      association {
        patterns_to_match = ["/*"]

        domain {
          cdn_frontdoor_domain_id = azurerm_cdn_frontdoor_endpoint.main.id
        }

        dynamic "domain" {
          for_each = azurerm_cdn_frontdoor_custom_domain.this

          content {
            cdn_frontdoor_domain_id = domain.value.id
          }
        }
      }
    }
  }
}
