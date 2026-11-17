variable "resource_group_name" {
  type = string
}

variable "suffix" {
  type = string
}

variable "tags" {
  type = map(string)
}

variable "web_origin_host" {
  description = "Host of the static website endpoint of the storage account."
  type        = string
}

variable "gateway_origin_host" {
  description = "Default host name of the gateway function app."
  type        = string
}

variable "custom_domains" {
  type = map(string)
}

variable "rate_limit_api_per_minute" {
  type = number
}

variable "rate_limit_all_per_minute" {
  type = number
}
