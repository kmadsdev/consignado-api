# Operations runbook

For the owner of the platform. Resource names are real. No ID, key or address is written here: read them from Terraform outputs or Key Vault when a command needs one.

Back to the [entry page](README.md).

Steps marked **(not executed)** follow from the code and the reports, but no report shows that someone ran them on this platform.

## 1. Deploy

| Part | Repository | How | Trigger |
|---|---|---|---|
| Azure resources | `consignado-api`, `infra/terraform` | Terraform, by hand | The owner |
| API on AKS | `consignado-api` | `.github/workflows/ci.yml`, job `deploy` | Push to `main` |
| Gateway | `consignado-serverless` | `.github/workflows/gateway.yml` | Push to `main` |
| API on Functions | `consignado-serverless` | `.github/workflows/api-host.yml` | Push to `main`, or by hand with an API ref |
| Site | `consignado-front` | `.github/workflows/deploy.yml` | Push to `main` |
| Edge Worker | `consignado-front`, `edge/` | `wrangler`, by hand | The owner |

All workflows sign in with GitHub OIDC as the identity `id-consignado-github`. Each repository holds three secrets (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`). They are IDs, not passwords; they are secrets only so that GitHub masks them in public logs.

### Terraform

```bash
cd infra/terraform
az login                                   # as the Owner of the subscription
./bootstrap.sh                             # one time: the state storage
terraform init
terraform fmt -check && terraform validate
terraform plan -out=tfplan
terraform apply tfplan
terraform output -json > outputs.json      # commit this file; no secret is an output
```

- A stopped cluster cannot be changed. If the plan changes `aks-consignado`, start it first: `az aks start -g rg-consignado -n aks-consignado`.
- New role assignments need one or two minutes. If the first apply fails with a 403, apply again.
- App settings of the function apps belong to Terraform. A setting made in the portal or with the CLI is removed by the next apply. Add it in `functions.tf`.
- When Cloudflare changes its address list, change the variable `gateway_allowed_cidrs` and apply.

### API on AKS

A push to `main` builds and tests, builds the image, pushes it to `crconsignadokm01`, starts the cluster when it is stopped, applies `deploy/k8s`, waits for the rollout, runs a smoke test inside the cluster and checks that the Service holds its private address. The job does not stop the cluster. The gateway timer stops it after 20 minutes with no window.

If Azure refuses the start because a stop was recent, the job tries 3 times with 3 minutes between tries, then fails. Run the job again.

By hand:

```bash
az aks get-credentials -g rg-consignado -n aks-consignado
kubelogin convert-kubeconfig -l azurecli
kubectl -n consignado get pods
```

### Gateway

A push to `main` builds, runs the 77 tests, and deploys the staged function app to `func-consignado-gw-km01`. By hand:

```bash
cd gateway && ./mvnw -B -ntp verify
cd target/azure-functions/func-consignado-gw-km01 && rm -f local.settings.json && zip -qr ../gateway.zip .
az functionapp deployment source config-zip -g rg-consignado -n func-consignado-gw-km01 --src ../gateway.zip
```

### API on Functions

A push to `main` builds the package from the API source, proves it in a Functions host container with PostgreSQL beside it, and deploys the zip to `func-consignado-api-km01`. To deploy a specific API version:

```bash
gh workflow run api-host.yml -R kmadsdev/consignado-serverless -f api_ref=main
```

By hand (needs Docker):

```bash
cd api-host && ./build.sh --ref main && ./prove-local.sh
az functionapp deployment source config-zip -g rg-consignado -n func-consignado-api-km01 --src dist/api-host.zip
```

After the first deploy, put the host key of the function app in Key Vault as `apifn-host-key`. Do not print it. In the first logs, look for `Started ConsignadoApplication in` and for `[warning][cds]` (the start is slower when that warning shows).

### Site

A push to `main` builds the site and uploads it to the static website of `stconsigwebkm01`. Hashed assets go first and the HTML last, so a page never points to a file that is not there yet.

### Edge Worker

```bash
cd edge && npm install
npx wrangler deploy --dry-run
npx wrangler deploy          # needs a Cloudflare API token with Workers and DNS rights on the zone
```

A Cloudflare token is not stored in any repository.

### Smoke test after a deploy

```bash
B=https://consignado.kmads.dev
curl -s $B/api/health                                      # {"status":"UP"}
curl -s $B/api/engine                                      # {"state":"stopped",...}
curl -s -o /dev/null -w '%{http_code}\n' $B/api/me         # 401
curl -s -o /dev/null -w '%{http_code}\n' $B/api/aks/api/v1/customers   # 401 for a guest
curl -si -X POST $B/api/fn/api/v1/simulations/preview -H 'content-type: application/json' \
  -d '{"principal":10000,"termMonths":24,"monthlyInterestRate":0.018}' | grep -i -E '^HTTP|x-consignado'
```

A direct call to the gateway function app, not through the edge, must answer 403.

## 2. Pause and resume the engine

Open `https://consignado.kmads.dev/admin` with the owner's account, section "Engine and budget".

| Action | Effect |
|---|---|
| **Pause the engine** | No new window can start. Open windows continue to their end, then the cluster stops. The serverless host is not affected. |
| **Resume** | Users can start windows again. |
| **Stop the engine now** | Ends all open windows at once and stops the cluster. Users lose the remaining time. |

The same actions as API calls: `POST /api/admin/pause` with `{"paused": true}` or `{"paused": false}`, and `POST /api/admin/engine/stop`. Every admin call writes an audit row.

A pause by the budget alert (reason `budget`) is lifted the same way, with "Resume". Read section 6 first.

## 3. Stop the cluster by hand

```bash
az aks show -g rg-consignado -n aks-consignado --query '{power:powerState.code,state:provisioningState}'
az aks stop -g rg-consignado -n aks-consignado
```

Use this when the gateway is not deployed or does not work: nothing else stops a running cluster. A running cluster costs about US$2.50 per day.

After a stop, wait before a start. The engine uses a cool-down of 15 minutes; the AKS guidance is 15 to 30 minutes.

If the gateway is running, prefer "Stop the engine now" in `/admin`, so the engine state and the sessions stay correct. The gateway also reads the real power state, so a stop by hand is seen at the next timer run.

## 4. Cost

Prices: Azure retail prices API, read on 2026-11-17, US dollars, pay-as-you-go, 730 hours per month. Lines marked (estimate) depend on use.

### Per month with the cluster stopped

| Item | US$ per month |
|---|---|
| PostgreSQL B1ms compute | 12.41 |
| PostgreSQL storage, 32 GB | 3.68 |
| Registry, Basic | 5.00 |
| AKS node disk, managed 32 GB | 5.81 |
| AKS outbound public IP | 3.65 |
| AKS load balancer | 0 to 18.25 (estimate; not verified whether it is billed while the cluster is stopped) |
| Function apps, Flex Consumption | 0 to 1 (estimate) |
| Storage, 3 accounts | below 0.50 (estimate) |
| Key Vault | below 0.10 (estimate) |
| Log Analytics and Application Insights | 0 to 3 (estimate; daily cap 1 GB) |
| Email | below 0.10 (estimate) |
| Cloudflare Worker | 0 (free plan, 100 000 requests per day) |
| AKS control plane, free tier | 0 |
| **Total** | **about 31 to 35 fixed; up to about 53 if the load balancer line is billed in full** |

### Per window

| Item | US$ |
|---|---|
| Node `Standard_D2as_v4`, per hour | 0.104 |
| One 15-minute window, node time only | 0.026 |
| 100 windows | 2.60 |
| Cluster left on for a month | 75.92 |

The node also runs during the start, the stop and the 20-minute grace period after a deploy, so the real cost of a window is higher than 0.026. That extra was not measured.

The database can be stopped for 7 days at most with `az postgres flexible-server stop -g rg-consignado -n psql-consignado-km01`. Azure starts it again after that. Both hosts fail while it is stopped.

## 5. Rotate secrets

### Edge key **(not executed)**

The key is made by Terraform (`random_password.edge_key`), stored in Key Vault as `edge-key`, and copied into the Worker as the secret `EDGE_KEY`. The gateway caches secrets for 5 minutes.

```bash
cd infra/terraform
terraform apply -replace=random_password.edge_key
az keyvault secret show --vault-name kv-consig-km01 -n edge-key --query value -o tsv | npx wrangler secret put EDGE_KEY
```

Run the second command from the `edge/` folder of `consignado-front`, right after the first. Between the two changes, and for up to 5 minutes after (the gateway cache), the API can answer 403. Do it at a quiet time. The value goes through a pipe and is never printed.

### Database password **(not executed)**

The password is made by Terraform (`random_password.db`), set on the server and stored in Key Vault as `db-password`.

```bash
cd infra/terraform
terraform apply -replace=random_password.db
```

Then make both hosts read the new value:

- AKS host: the pod reads the password at start. Start the cluster and restart the deployment (`kubectl -n consignado rollout restart deployment`), or let the next window start fresh pods.
- Serverless host: `DB_PASSWORD` is a Key Vault reference. How fast the platform reads a new secret version was not verified; restart the function app to be sure (`az functionapp restart -g rg-consignado -n func-consignado-api-km01`).

### Other secrets

`apifn-host-key`: make a new host key on the API host function app, then set the Key Vault secret. `turnstile-secret`: rotate it in the Cloudflare dashboard, then set the Key Vault secret. `hash-key`: do not rotate it without a plan. A new key makes all stored hashes of IP addresses and fingerprints useless for the duplicate check.

## 6. When the budget alert fires

1. **50 or 80 percent.** An email goes to the owner. The gateway writes an audit row only. Check `/admin`, "Engine and budget", and the cost view of the resource group. Look for a cluster that was left running.
2. **100 percent.** The action group calls the gateway webhook. The gateway pauses the engine (reason `budget`), ends the open windows and stops the cluster. The site and the serverless host continue.
3. Confirm the stop: `az aks show -g rg-consignado -n aks-consignado --query powerState.code`. If it is not `Stopped`, stop it by hand (section 3). Delivery of the alert and the webhook was not verified live, so do not rely on step 2 alone.
4. Find the cause before a resume: many windows, a cluster left on by a deploy, log ingestion, or the load balancer line.
5. Leave the engine paused until the next month, or resume it in `/admin` and accept the extra cost.

The alert is not a hard cap. The fixed costs of section 4 continue while the engine is paused. To stop all cost, delete the resources (section 8).

## 7. Remove a user's data on request

The gateway has no delete route. The owner does this by hand, with a data role on the storage account (the account accepts no key; Terraform does not give the owner a table data role, so assign one first). **(not executed)**

| Where | What to remove |
|---|---|
| Table `users` in `stconsigappkm01` | The row of the user. The row key is the user's object id from the token. It holds email, name, company, LinkedIn URL, status, window counts, flags, the hashes of IP and fingerprint, and the consent time. |
| Table `sessions` | All rows with that `userId`. |
| Table `contacts` | All rows with the user's email (contact and "more windows" requests). |
| Table `audit` | Rows where the user is the actor or the target. These are security logs with a 90-day period; remove them when the user asks and no security reason remains. |
| Entra ID tenant | The guest user object that the sign-up created. |
| Application Insights | Log rows are kept for 30 days and then removed by the workspace. |

The shared loan data in PostgreSQL is synthetic and is not linked to a user: the API does not record who made a row.

Answer the person by email from the contact form thread when it is done.

## 8. Delete everything

```bash
cd infra/terraform
terraform destroy                                  # all resources of the platform
az keyvault purge -n kv-consig-km01                # only if the vault name must be free again (a deleted vault is kept for 90 days)
az group delete --name rg-consignado-tfstate       # only when the state is no longer necessary
```

Not managed by Terraform; remove by hand:

- The Worker `consignado-edge` and its two custom domains at Cloudflare; the Turnstile widget.
- In the Entra ID tenant: the user flow `B2X_1_consignado`; the guest users that signed up; and the tenant settings that were changed for the sign-up (self-service sign-up on, guest permissions set to most restricted, invitations limited to admins) if the old values are wanted back.
- The repository secrets and variables in the three GitHub repositories.

Check: `az group list --query "[?name=='rg-consignado'].name" -o tsv` must print nothing.

## Example

A hypothetical case. The owner gets the 80 percent email on the 20th of the month. In `/admin` the engine shows `running` with no active session for two hours. The owner clicks "Stop the engine now", confirms `Stopped` with `az aks show`, then looks for the cause in the audit rows and the gateway logs before the next window is allowed.
