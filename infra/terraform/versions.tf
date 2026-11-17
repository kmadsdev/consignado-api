terraform {
  required_version = ">= 1.9"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.40"
    }
    azuread = {
      source  = "hashicorp/azuread"
      version = "~> 3.4"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # State: made by bootstrap.sh. Entra ID auth, no account key.
  backend "azurerm" {
    resource_group_name  = "rg-consignado-tfstate"
    storage_account_name = "stconsigtfkm01"
    container_name       = "tfstate"
    key                  = "platform.tfstate"
    use_azuread_auth     = true
  }
}

provider "azurerm" {
  subscription_id = var.subscription_id

  # The providers are registered already; a student subscription refuses some of the full list.
  resource_provider_registrations = "none"

  # Storage data plane (containers, tables, static website) through Entra ID, not account keys.
  storage_use_azuread = true

  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}

provider "azuread" {}

provider "random" {}
