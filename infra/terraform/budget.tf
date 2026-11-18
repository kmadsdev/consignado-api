resource "azurerm_monitor_action_group" "budget" {
  count = var.enable_budget ? 1 : 0

  name                = "ag-consignado-budget"
  resource_group_name = local.rg_name
  short_name          = "consigbudg"
  tags                = local.tags

  email_receiver {
    name                    = "owner"
    email_address           = var.owner_email
    use_common_alert_schema = true
  }

  # var.budget_webhook_url holds a function key: set it with TF_VAR_budget_webhook_url (README, step 2).
  dynamic "webhook_receiver" {
    for_each = var.budget_webhook_url == "" ? [] : [var.budget_webhook_url]

    content {
      name                    = "gateway"
      service_uri             = webhook_receiver.value
      use_common_alert_schema = true
    }
  }
}

resource "azurerm_consumption_budget_subscription" "main" {
  count = var.enable_budget ? 1 : 0

  name            = "budget-consignado"
  subscription_id = data.azurerm_subscription.current.id
  amount          = var.budget_amount
  time_grain      = "Monthly"

  time_period {
    start_date = var.budget_start_date
  }

  dynamic "notification" {
    for_each = [50, 80]

    content {
      enabled        = true
      threshold      = notification.value
      threshold_type = "Actual"
      operator       = "GreaterThanOrEqualTo"
      contact_emails = [var.owner_email]
    }
  }

  notification {
    enabled        = true
    threshold      = 100
    threshold_type = "Actual"
    operator       = "GreaterThanOrEqualTo"
    contact_emails = [var.owner_email]
    contact_groups = [azurerm_monitor_action_group.budget[0].id]
  }

  lifecycle {
    ignore_changes = [time_period]
  }
}
