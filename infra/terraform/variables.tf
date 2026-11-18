variable "subscription_id" {
  description = "Subscription \"Azure for Students\"."
  type        = string
  default     = "843b8ff6-4318-4199-9144-da130b3ee4ea"
}

variable "location" {
  description = "Main region. The subscription policy allows only: belgiumcentral, northcentralus, chilecentral, mexicocentral, southafricanorth."
  type        = string
  default     = "belgiumcentral"
}

variable "pg_location" {
  description = "Region of the PostgreSQL server. belgiumcentral had no B1ms capacity (RegionalAllocationFailed, 2026-11-17)."
  type        = string
  default     = "northcentralus"
}

variable "pg_server_name" {
  description = "Name of the PostgreSQL server. A failed create reserves the name in that region for some time: then add a suffix here."
  type        = string
  default     = "psql-consignado-km01"
}

variable "suffix" {
  description = "Suffix of the globally unique names."
  type        = string
  default     = "km01"
}

variable "owner_email" {
  description = "Owner: budget alerts and gateway notifications."
  type        = string
  default     = "kaiquemoreira520@gmail.com"
}

variable "custom_domains" {
  description = "Front Door custom domains (key = resource name)."
  type        = map(string)
  default = {
    consignado         = "consignado.kmads.dev"
    cluster-consignado = "cluster.consignado.kmads.dev"
  }
}

variable "github_owner" {
  description = "GitHub owner login and numeric id (immutable OIDC subject format)."
  type = object({
    login = string
    id    = number
  })
  default = {
    login = "kmadsdev"
    id    = 158073300
  }
}

variable "github_repositories" {
  description = "Repositories that deploy from the main branch: name => numeric repository id."
  type        = map(number)
  default = {
    consignado-api        = 1456253050
    consignado-front      = 1460963568
    consignado-serverless = 1460963640
  }
}

variable "aks_node_vm_size" {
  type    = string
  default = "Standard_D2as_v4"
}

variable "aks_internal_lb_ip" {
  description = "Fixed IP of the internal load balancer that Kubernetes creates in snet-aks."
  type        = string
  default     = "10.10.3.200"
}

variable "entra_sign_in_audience" {
  description = "Who can sign in to the two app registrations. The integrator decides (user flows are not managed here)."
  type        = string
  default     = "AzureADMyOrg"
}

variable "budget_amount" {
  description = <<-EOT
    Monthly budget of the subscription, in the BILLING CURRENCY of the subscription (var.budget_currency, BRL here),
    not in US dollars: Azure keeps a budget amount in the billing currency. 330 = the owner's US$60 at about
    R$5.50 per US dollar (rate of 2026-11-18). The owner can change it. The gateway gets the same number (BUDGET_LIMIT).
  EOT
  type        = number
  default     = 330
}

variable "budget_currency" {
  description = "ISO 4217 code of the billing currency of the subscription (\"Azure for Students\" is billed in BRL). Only a label for the gateway (BUDGET_CURRENCY); Azure takes the budget amount in this currency."
  type        = string
  default     = "BRL"
}

variable "budget_start_date" {
  description = "First day of the first budget month (must be the first of a month; do not change after create)."
  type        = string
  default     = "2026-11-01T00:00:00Z"
}

variable "budget_webhook_url" {
  description = "Gateway webhook that the action group calls at 100 percent. Empty = no webhook (the integrator sets it later)."
  type        = string
  default     = ""
}

variable "rate_limit_api_per_minute" {
  description = "WAF: requests per minute and client IP on /api/*."
  type        = number
  default     = 120
}

variable "rate_limit_all_per_minute" {
  description = "WAF: requests per minute and client IP on all paths."
  type        = number
  default     = 600
}

# Feature flags: set to false when the subscription refuses a resource type.
variable "enable_front_door" {
  description = "Front Door Standard + WAF. Refused on \"Azure for Students\": \"Free Trial and Student account is forbidden for Azure Frontdoor resources\"."
  type        = bool
  default     = false
}

variable "edge" {
  description = "The edge in front of the gateway and the site. `cloudflare` = a Cloudflare Worker (owner's decision, 2026-11-17, because Front Door is refused)."
  type        = string
  default     = "cloudflare"
}

variable "gateway_allowed_cidrs" {
  description = "Only when enable_front_door = false: the IPv4 ranges that can call the gateway. Default: the Cloudflare ranges of https://www.cloudflare.com/ips-v4, read on 2026-11-17. Empty = all sources (HTTPS only)."
  type        = list(string)
  default = [
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
  ]
}

variable "enable_email" {
  type    = bool
  default = true
}

variable "email_data_location" {
  type    = string
  default = "Europe"
}

variable "enable_budget" {
  type    = bool
  default = true
}

variable "enable_function_vnet_integration" {
  description = "VNet integration of the gateway function app with snet-func."
  type        = bool
  default     = true
}

# Cost guard (security review M3, 2026-11-17): anonymous load on the guest route cannot grow past these caps.
variable "gateway_max_instances" {
  description = "Cost guard: maximum instances of the gateway (Flex Consumption, 2048 MB each)."
  type        = number
  default     = 2
}

variable "api_host_max_instances" {
  description = "Cost guard: maximum instances of the API host (Flex Consumption, 2048 MB each)."
  type        = number
  default     = 1
}
