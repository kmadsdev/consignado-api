# Terraform: consignado platform on Azure

This directory creates the Azure resources of the platform (contract section 2) in the subscription
"Azure for Students". State is remote (Azure Storage, Entra ID auth, no account key).

> **Front Door is not created.** The subscription refuses it: `BadRequest: Free Trial and Student account is forbidden for
> Azure Frontdoor resources.` The code is in `modules/edge` behind `enable_front_door` (default `false`). The edge is a
> Cloudflare Worker (owner's decision, 2026-11-17); see "Decisions".

- Terraform >= 1.9 (made with 1.16), providers `azurerm` 4.x, `azuread` 3.x, `random` 3.x (versions pinned in `.terraform.lock.hcl`).
- Sign in first: `az login` as the Owner of the subscription. The same user must be able to create app registrations in the tenant.
- Non-secret results: [`outputs.json`](outputs.json) (committed). Secrets are only in Key Vault and in the remote state.

## Commands

```bash
cd infra/terraform

# 1. One time: resource group rg-consignado-tfstate, storage stconsigtfkm01, container tfstate,
#    role "Storage Blob Data Contributor" for the signed-in user.
./bootstrap.sh

# 2. Each change. ALWAYS set the budget webhook first, in the same shell (it is in no file; see the note below).
export TF_VAR_budget_webhook_url="https://consignado.kmads.dev/api/hooks/budget?code=$(az functionapp function keys list -g rg-consignado -n func-consignado-gw-km01 --function-name budgetHook --query default -o tsv)"
terraform init -input=false
terraform fmt -check && terraform validate
terraform plan -out=tfplan
terraform apply tfplan
terraform output -json > outputs.json      # commit this file (no secret, no tenant / subscription / person id is an output)

# 3. Remove everything of the platform (NOT the state storage, NOT rg-consignado-lab)
terraform destroy
az group delete --name rg-consignado-tfstate   # only when the state is no longer necessary
```

Notes for operation:

- **A stopped cluster cannot be changed.** `terraform plan` works while `aks-consignado` is stopped, but an apply that
  changes the cluster fails. Start the cluster first (`az aks start -g rg-consignado -n aks-consignado`).
- **Role assignments need time.** The first apply can fail on the Key Vault secret or the static website with a 403:
  run `terraform apply` again after one or two minutes.
- **App settings are owned by Terraform.** A setting that is added with the portal or the CLI is removed by the next apply.
  Add new settings in `functions.tf`.
- **PostgreSQL region.** If `Standard_B1ms` has no capacity (`RegionalAllocationFailed`), set `-var pg_location=<other allowed region>`.
  A failed create reserves the server name in the first region for some time: also set `-var pg_server_name=psql-consignado-km01-<n>`.
- **Budget webhook: `TF_VAR_budget_webhook_url` on EVERY plan and apply.** The action group `ag-consignado-budget` calls the
  gateway through the edge (`https://consignado.kmads.dev/api/hooks/budget?code=<function key>`). The URL holds a function key,
  so it is in no file: set it with the `export` line in step 2, in the same shell, and never print it. Without it, the variable
  is empty and the apply REMOVES the webhook from the action group (then the budget sends email only).
- **Live window first.** An apply that changes a function app restarts it. Apply only when
  `curl -s https://consignado.kmads.dev/api/engine` shows `"activeSessions":0`.
- **Feature flags.** `enable_front_door` (default `false`), `enable_email`, `enable_budget`, `enable_function_vnet_integration` switch a part that the subscription refuses.
- **Cloudflare ranges.** `gateway_allowed_cidrs` holds the list of `https://www.cloudflare.com/ips-v4` of 2026-11-17. When Cloudflare changes the list, change the variable and apply.
- The subscription policy allows only `belgiumcentral`, `northcentralus`, `chilecentral`, `mexicocentral`, `southafricanorth`.

## Files

| File | Content |
|---|---|
| `bootstrap.sh` | State storage (az CLI). |
| `versions.tf` | Providers, backend. |
| `main.tf` | Resource group, tags, shared locals. |
| `network.tf` | `vnet-consignado`, `snet-aks`, `snet-func`. |
| `aks.tf` | Registry and AKS cluster. |
| `data.tf` | PostgreSQL, Key Vault and its secrets (`db-password`, `edge-key`, `hash-key`), the three storage accounts, containers, tables. |
| `functions.tf` | The two Flex Consumption function apps and their plans. |
| `edge.tf` + `modules/edge/` | Front Door profile, endpoint, origins, routes, rule sets, custom domains, WAF. Off (`enable_front_door = false`). The one module of this stack: it lets one flag switch 18 resources. |
| `identity.tf` | Managed identities, federated credentials, all role assignments, the custom role, Entra app registrations. |
| `monitoring.tf` | Log Analytics, Application Insights, Communication Services (email). |
| `budget.tf` | Budget and action group. |

## Resources

| Thing | Name | Region | Notes |
|---|---|---|---|
| Resource group | `rg-consignado` | belgiumcentral | Tags `project=consignado`, `managed-by=terraform` on every resource. |
| Virtual network | `vnet-consignado` 10.10.0.0/16 | belgiumcentral | `snet-aks` 10.10.0.0/22; `snet-func` 10.10.8.0/24 (delegation `Microsoft.App/environments`, service endpoint `Microsoft.Web`). |
| AKS | `aks-consignado` | belgiumcentral | Free tier, 1 x `Standard_D2as_v4`, Azure CNI overlay (pods 10.244.0.0/16, services 10.0.0.0/16), OIDC issuer, workload identity, Key Vault CSI add-on, Entra ID + Azure RBAC, local accounts off, managed OS disk 32 GB. |
| Registry | `crconsignadokm01` | belgiumcentral | Basic, admin user off. |
| Key Vault | `kv-consig-km01` | belgiumcentral | RBAC mode, soft delete 7 days, purge protection on (since 2026-11-17; it cannot be turned off). Secrets `db-password` and `edge-key` (Terraform). `apifn-host-key`, `turnstile-secret`: the integrator. |
| PostgreSQL | `psql-consignado-km01` | northcentralus (`pg_location`) | Flexible server `B_Standard_B1ms`, v16, 32 GB, database `consignado`, admin `pgadmin`, public endpoint + rule `AllowAllAzureServices`. |
| Storage (site) | `stconsigwebkm01` | belgiumcentral | Static website (`index.html`, error document `index.html`), no account key. |
| Storage (app) | `stconsigappkm01` | belgiumcentral | Gateway only: host storage, containers `deploy-gateway`, `data`; tables `users`, `sessions`, `engine`, `contacts`, `audit` (ARM deployment `consignado-tables`); no account key. Container `deploy-api` is the OLD API host package (not used since 2026-11-17; delete it after the new one is proved). |
| Storage (API host) | `stconsigapifnkm01` | belgiumcentral | API host only: host storage and container `deploy-api` (deployment package). No account key, no public blob access, TLS 1.2. Security review M4: the API host identity cannot write the gateway package. |
| Monitoring | `log-consignado`, `appi-consignado` | belgiumcentral | Daily cap 1 GB, 30 days. |
| Gateway | `func-consignado-gw-km01` (plan `asp-consignado-gw-km01`) | belgiumcentral | Flex Consumption, Java 21, 2048 MB, max 2 instances (`gateway_max_instances`), VNet integration `snet-func`, inbound: the 15 Cloudflare IPv4 ranges only, all other sources denied (with Front Door: `AzureFrontDoor.Backend` + `X-Azure-FDID`). |
| API host | `func-consignado-api-km01` (plan `asp-consignado-api-km01`) | belgiumcentral | Flex Consumption, runtime `custom`, 2048 MB, max 1 instance (`api_host_max_instances`), inbound: `snet-func` only. Storage: `stconsigapifnkm01`. |
| Front Door (**not created, refused**) | `afd-consignado` (Standard), endpoint `consignado-km01` | global | Routes `/api/*` -> gateway (no cache), `/*` -> static site (cache + SPA rewrite); security headers; 2 custom domains with managed certificates. |
| WAF (**not created, refused**) | `wafconsignado` | global | Prevention. Rate limits per client IP: 120/min on `/api/`, 600/min on all paths. |
| Email | `acs-consignado-km01`, `acs-email-consignado-km01`, `AzureManagedDomain` | global (data: Europe) | Sender `DoNotReply@<guid>.azurecomm.net`. |
| Identities | `id-consignado-app`, `-gw`, `-apifn`, `-github` | belgiumcentral | User-assigned. Federated credentials: pods, and the `main` branch of the three GitHub repositories. |
| Custom role | `Consignado Engine Operator` | scope `rg-consignado` | read / start / stop / agentPools read / listClusterUserCredential. No write, no delete. |
| Entra apps | `consignado-spa`, `consignado-api-gateway` | tenant | No client secrets. Scope `api://<client id>/access_as_user`, token v2, SPA pre-authorized. |
| Budget | `budget-consignado`, action group `ag-consignado-budget` | subscription | US$60 per month; 50, 80, 100 percent to the owner; 100 percent also calls the action group. |

### Role assignments

| Principal | Role | Scope |
|---|---|---|
| Signed-in user | Azure Kubernetes Service RBAC Cluster Admin | cluster |
| Signed-in user | Key Vault Secrets Officer | Key Vault |
| Cluster identity | Network Contributor | `snet-aks` (to create the internal load balancer) |
| Kubelet identity | AcrPull | registry |
| `id-consignado-app` | Key Vault Secrets User | Key Vault |
| `id-consignado-gw` | Key Vault Secrets User | Key Vault |
| `id-consignado-gw` | Storage Table Data Contributor, Storage Blob Data Contributor, Storage Blob Data Owner (host storage) | `stconsigappkm01` |
| `id-consignado-gw` | Consignado Engine Operator, Azure Kubernetes Service RBAC Reader | cluster |
| `id-consignado-gw` | Cost Management Reader | `rg-consignado` |
| `id-consignado-gw` | Monitoring Reader (live status: metrics, resource health, resource state; read-only) | `rg-consignado` |
| `id-consignado-gw` | Communication and Email Service Owner | `acs-consignado-km01` |
| `id-consignado-apifn` | Key Vault Secrets User | Key Vault |
| `id-consignado-apifn` | Storage Blob Data Owner (host and deployment storage) | `stconsigapifnkm01` only (no role on `stconsigappkm01`) |
| API host, system-assigned identity | Key Vault Secrets User (Key Vault reference of `DB_PASSWORD`) | Key Vault |
| `id-consignado-github` | AcrPush | registry |
| `id-consignado-github` | Azure Kubernetes Service Cluster User Role, Azure Kubernetes Service RBAC Writer, Consignado Engine Operator | cluster |
| `id-consignado-github` | Website Contributor | both function apps |
| `id-consignado-github` | Storage Blob Data Contributor | `stconsigappkm01`, `stconsigapifnkm01`, `stconsigwebkm01` |
| `id-consignado-github` | CDN Endpoint Contributor (purge) | Front Door profile: only when `enable_front_door = true` (not assigned now) |

## Cost per month

Prices: Azure retail prices API (`https://prices.azure.com/api/retail/prices`), read on 2026-11-17, USD, pay-as-you-go.
A month = 730 hours. Lines with **(estimate)** depend on use.

| Item | Unit price | Cluster stopped (normal state) | Notes |
|---|---|---|---|
| Front Door Standard, base fee | 35.00 / month | 0 (not created) | Refused on this subscription. On a subscription that allows it: 35.00, the largest fixed cost. |
| Cloudflare Worker (edge) | 0 | 0 | Free plan: 100 000 requests per day. Not an Azure cost. |
| PostgreSQL B1ms compute (northcentralus) | 0.017 / hour | 12.41 | Runs all the time. `az postgres flexible-server stop` saves it for 7 days at most (then Azure starts the server again). |
| PostgreSQL storage 32 GB | 0.115 / GB-month | 3.68 | Backup storage up to the server size is free. |
| Registry Basic | 0.1666 / day | 5.00 | 10 GB included. |
| AKS OS disk, managed 32 GB (P4) | 5.81 / month | 5.81 | Billed also while the cluster is stopped. |
| AKS outbound public IP (Standard, static) | 0.005 / hour | 3.65 | Stays while the cluster is stopped. |
| AKS load balancer (Standard, <= 5 rules) | **(estimate)** 0.025 / hour | **(estimate)** 0 to 18.25 | The retail API returned no meter for this region. Billed when rules exist; UNVERIFIED whether it is billed while the cluster is stopped. |
| AKS control plane | 0 | 0 | Free tier. |
| AKS node `Standard_D2as_v4` | 0.104 / hour | 0 | Only while the cluster runs: 0.026 for one 15-minute window. 100 windows = 2.60. 730 hours = 75.92 (never leave it on). |
| Function apps (Flex Consumption, on demand) | 0.000034 / GB-second, 0.0000005 / execution | **(estimate)** 0 to 1 | Monthly free grant (100 000 GB-seconds, 250 000 executions). No always-ready instances. |
| Storage (4 accounts, LRS, hot) | 0.018 / GB-month | **(estimate)** < 0.50 | Site, deployment packages, tables, Terraform state. |
| Key Vault Standard | per 10 000 operations | **(estimate)** < 0.10 | |
| Log Analytics / Application Insights | 2.99 / GB ingested | **(estimate)** 0 to 3 | The first 5 GB per month are free; daily cap 1 GB. |
| Communication Services email | per message | **(estimate)** < 0.10 | |
| Budget, action group, identities, VNet, WAF policy (Standard tier), Entra apps | 0 | 0 | |
| **Total (as built, no Front Door)** | | **about 31 to 35 fixed; up to about 53 if the load balancer line is billed in full** | |

The fixed cost is below the US$60 budget, mainly PostgreSQL (16.09), the registry (5.00), the OS disk (5.81) and the public IP (3.65).
With Front Door Standard (+35.00) the fixed cost would be about 66 to 70: higher than the budget. Keep that in mind before
`enable_front_door = true` on a different subscription. A running cluster adds 2.50 per day.

## Decisions and trade-offs

- **Edge: Cloudflare Worker, not Front Door (2026-11-17).** The offer "Azure for Students" refuses Front Door
  (`Free Trial and Student account is forbidden for Azure Frontdoor resources`). The owner chose Cloudflare: the DNS zone
  `kmads.dev` is there already, the Worker costs nothing, and it gives TLS for the custom domains, a cache for the site and
  rate limits. What replaces the Front Door controls: (1) the gateway function app accepts only the Cloudflare IPv4 ranges
  (`gateway_allowed_cidrs`, 15 rules, default action Deny); (2) those ranges are shared by all Cloudflare customers, so the
  gateway also checks a shared header: the value is the Key Vault secret `edge-key` (48 random characters, made here; the
  integrator copies it into the Worker as a secret); (3) app setting `EDGE=cloudflare` tells the gateway which edge it has.
  Trade-off: no Azure WAF, the IP list must be kept current by hand, and the SPA rewrite and the security headers move from
  the rule engine into the Worker. The static site stays in `stconsigwebkm01` (output `edge.static_site_host`).
- **Front Door Standard (the first design, kept in `modules/edge`).** It gives one public entry for the site and the API, a CDN, managed certificates for the custom
  domains, a rule engine (SPA rewrite, security headers) and a WAF with rate limits. Premium adds managed WAF rule sets and
  Private Link origins, but costs US$330 per month. Standard supports custom WAF rules only; that is why the WAF has only
  rate-limit rules. The gateway accepts only the `AzureFrontDoor.Backend` service tag with the `X-Azure-FDID` header of this
  profile, so a different Front Door profile cannot reach it.
- **Internal load balancer.** The AKS service has no public IP. Kubernetes creates an internal load balancer with the fixed IP
  `10.10.3.200` in `snet-aks`; only the VNet can reach it, and the gateway is in the VNet (`snet-func`). The cluster identity has
  `Network Contributor` on `snet-aks` for this. Terraform does not create the load balancer: the Service manifest in `deploy/k8s` does.
- **Database with a public endpoint and the Azure-services rule.** Owner's decision for cost. A private endpoint costs about
  US$7 per month plus a private DNS zone, and VNet injection of the server makes the API host function app need VNet integration
  and a cross-region link (the server is in `northcentralus`). Protection that stays: TLS is required, the password has 28
  random characters and is only in Key Vault, and the firewall rule 0.0.0.0 admits only addresses of Azure, not the internet.
  The weak point: any Azure tenant can reach the port, so the password is the real control.
- **Azure RBAC for Kubernetes.** One system of permissions (Azure role assignments, in this Terraform) for people, the
  gateway and CI. No kubeconfig with a client certificate exists (`local_account_disabled = true`), so nothing can leak
  from a file. Each caller gets a token from Entra ID: the gateway reads (`RBAC Reader`), CI deploys (`RBAC Writer`),
  the owner is `RBAC Cluster Admin`. `kubectl` needs `kubelogin`.
- **OS disk of the node: managed, 32 GB.** An ephemeral OS disk (48 GB in the 50 GiB cache of `Standard_D2as_v4`) has no
  disk cost. The Microsoft documentation for cluster stop/start (`learn.microsoft.com/azure/aks/start-stop-cluster`, read
  2026-11-17) does not list ephemeral OS disks as a limit, but it also does not say that they are supported; only the
  "scale-down mode: deallocate" page says "Ephemeral OS disks aren't supported". Not confirmed -> managed disk, as the owner
  said. Cost: US$5.81 per month. To change later: set `os_disk_type = "Ephemeral"`, `os_disk_size_gb = 48` (the node pool is replaced).
- **Password in state.** `random_password` keeps the database password in the Terraform state. The state storage has no
  public access, no shared key and Entra ID auth only.
- **Key Vault reference of `DB_PASSWORD`.** The provider resource `azurerm_function_app_flex_consumption` has no
  `key_vault_reference_identity_id`. The platform then resolves references with the system-assigned identity, so the API host
  has a system-assigned identity too, with `Key Vault Secrets User`.
- **Host storage of the function apps.** `AzureWebJobsStorage__accountName` + `__credential=managedidentity` + `__clientId`:
  no connection string. The host needs `Storage Blob Data Owner`. Each app has its own account (security review M4,
  2026-11-17): the gateway on `stconsigappkm01`, the API host on `stconsigapifnkm01`. Before, both shared `stconsigappkm01`,
  so code in the API host could replace the gateway package and run as the gateway identity.
- **Scale caps (security review M3, 2026-11-17).** Anonymous visitors reach both apps through the guest route
  `POST /api/fn/api/v1/simulations/preview`. The caps limit that spend: gateway 2 instances, API host 1 instance,
  2048 MB each (variables `gateway_max_instances`, `api_host_max_instances`; before: 4 for both).
- **Gateway live status (contract addendum part 1).** The gateway identity has `Monitoring Reader` on `rg-consignado`,
  and the gateway app settings `GATEWAY_APP_ID`, `API_APP_ID`, `KEY_VAULT_ID`, `POSTGRES_ID` hold the resource ids it reads.
  `GATEWAY_APP_ID` is built from the resource group id and the name (a reference to the app itself is a cycle).
  The ids are not secrets, but they are not outputs: they hold the subscription id.
- **Outputs in a public repository (security review I4, 2026-11-17).** `outputs.json` holds names, host names and client
  ids only: no tenant id, no subscription id, no object id of a person, no ARM resource id. These values are not secrets;
  older versions of the file in the git history still show them.
- **No NAT gateway.** `snet-func` keeps the default outbound access of Azure (a NAT gateway costs about US$32 per month).
- **No health probe on the origins.** One origin per group: a probe changes nothing, and it would keep the function app awake.
- **App registrations.** `sign_in_audience` is a variable (`entra_sign_in_audience`, default `AzureADMyOrg`); user flows and
  policies are not managed here.
