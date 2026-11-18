# Operations runbook

For the admin of the platform (me). Resource names are real. No ID, key or address is written here: read them from Terraform outputs or Key Vault when a command needs one.

Back to the [entry page](README.md).

Steps marked **(not executed)** follow from the code and the reports, but no report shows that someone ran them on this platform.
Section 9 lists the incidents of the first live day, with cause and fix.

## 1. Deploy

| Part | Repository | How | Trigger |
|---|---|---|---|
| Azure resources | `consignado-api`, `infra/terraform` | Terraform, by hand | The admin |
| API on AKS | `consignado-api` | `.github/workflows/ci.yml`, job `deploy` | Push to `main` |
| Gateway | `consignado-serverless` | `.github/workflows/gateway.yml` | Push to `main` |
| API on Functions | `consignado-serverless` | `.github/workflows/api-host.yml` | Push to `main`, or by hand with an API ref |
| Site | `consignado-front` | `.github/workflows/deploy.yml` | Push to `main` |
| Edge Worker | `consignado-front`, `edge/` | `wrangler`, by hand | The admin |

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
- **Always set `TF_VAR_owner_email` before a plan or an apply.** The admin email address has no default in the public repository. Read it back from the gateway setting, without printing it: `export TF_VAR_owner_email="$(az functionapp config appsettings list -g rg-consignado -n func-consignado-gw-km01 --query "[?name=='OWNER_EMAIL'].value | [0]" -o tsv)"`.
- **Always set `TF_VAR_budget_webhook_url` before a plan or an apply.** The webhook URL of the budget action group holds a function key, so it is in no file. Terraform reads it from this variable. An apply without it removes the webhook from the action group, and the budget alert then sends email only. Read the URL from the place where you keep it and export it in the same shell, without printing it:

  ```bash
  export TF_VAR_budget_webhook_url="$(<command that prints the URL from your secret store>)"
  terraform plan -out=tfplan     # the action group must show no change
  ```

  The URL has the form `https://consignado.kmads.dev/api/hooks/budget?code=<function key of budgetHook>`. The route `/api/hooks/budget` is the one gateway route that does not need the edge key.

### API on AKS

A push to `main` builds and tests, builds the image, pushes it to `crconsignadokm01`, starts the cluster when it is stopped, applies `deploy/k8s`, waits for the rollout, runs a smoke test inside the cluster and checks that the Service holds its private address. The job does not stop the cluster. The gateway timer stops it after 20 minutes with no window.

The image build uses the official Maven image (`maven:3.9.11-eclipse-temurin-21`), not the Maven wrapper. The wrapper download of Maven failed 3 times on GitHub runners on the first day.

The deploy identity `id-consignado-github` has `Azure Kubernetes Service RBAC Admin` on the namespace `consignado` only, plus `Azure Kubernetes Service Cluster User Role` on the cluster. It cannot create a namespace and it cannot read nodes. The job waits until the API server answers for the namespace.

**Create the namespace once, as the cluster admin.** `deploy/render.sh` leaves `00-namespace.yaml` out unless `WITH_NAMESPACE=1` is set. On a new cluster:

```bash
az aks get-credentials -g rg-consignado -n aks-consignado
kubelogin convert-kubeconfig -l azurecli
# all variables of render.sh set as for a deploy (see the header of deploy/render.sh)
WITH_NAMESPACE=1 ./deploy/render.sh | kubectl apply -f -
```

`00-namespace.yaml` has no placeholder, so `kubectl apply -f deploy/k8s/00-namespace.yaml` does the same for the namespace alone. Only an identity with a cluster-scope role can do this (the admin account has `Azure Kubernetes Service RBAC Cluster Admin`). The namespace on the live cluster was created once this way, with `az aks command invoke`, before the deploy job ran in the namespace.

If Azure refuses the start because a stop was recent, the job tries 3 times with 3 minutes between tries, then fails. Run the job again.

By hand:

```bash
az aks get-credentials -g rg-consignado -n aks-consignado
kubelogin convert-kubeconfig -l azurecli
kubectl -n consignado get pods
```

### Gateway

A push to `main` builds, runs the 82 tests, and deploys the staged function app to `func-consignado-gw-km01`. By hand:

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

After the first deploy, put the host key of the function app in Key Vault as `apifn-host-key`. Do not print it. Pipe it:

```bash
az functionapp keys list -g rg-consignado -n func-consignado-api-km01 --query "functionKeys.default" -o tsv \
  | tr -d '\n' | az keyvault secret set --vault-name kv-consig-km01 -n apifn-host-key --file /dev/stdin --encoding utf-8 -o none
```

Run it again when the host key changes. The gateway caches the key for 5 minutes. In the first logs, look for `Started ConsignadoApplication in` and for `[warning][cds]` (the start is slower when that warning shows).

### Site

A push to `main` builds the site and uploads it to the static website of `stconsigwebkm01`. Hashed assets go first and the HTML last, so a page never points to a file that is not there yet.

### Edge Worker

The Worker `consignado-edge` is deployed by hand. No workflow deploys it, because a Cloudflare token is not stored in any repository.

```bash
cd edge && npm install
npx wrangler deploy --dry-run
npx wrangler deploy          # needs a Cloudflare API token with Workers rights on the account and the zone
```

The Worker needs the secret `EDGE_KEY`, with the same value as the Key Vault secret `edge-key`. Copy it through a pipe, from the `edge/` folder, so the value is never printed:

```bash
az keyvault secret show --vault-name kv-consig-km01 -n edge-key --query value -o tsv | npx wrangler secret put EDGE_KEY
```

The Cloudflare zone rules (rate limit, method allow-list, managed challenge) are set in the Cloudflare dashboard or API, not in the repository. They are scoped to the two host names. See the [entry page](README.md#status-2026-11-17-first-day-live).

### Smoke test after a deploy

```bash
B=https://consignado.kmads.dev
curl -s $B/api/health                                      # {"status":"UP"}
curl -s $B/api/engine                                      # {"state":"stopped",...} or cooldown, running
curl -s -o /dev/null -w '%{http_code}\n' $B/api/me         # 401
curl -s -o /dev/null -w '%{http_code}\n' $B/api/aks/api/v1/customers   # 401 for a guest
curl -si -X POST $B/api/fn/api/v1/simulations/preview -H 'content-type: application/json' \
  -d '{"principal":10000,"termMonths":24,"monthlyInterestRate":0.018}' | grep -i -E '^HTTP|x-consignado'
```

A direct call to the gateway function app, not through the edge, must answer 403.

## 2. Pause and resume the engine

Open `https://consignado.kmads.dev/admin` with the admin account, section "Engine and budget".

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

The automatic stop works: on 2026-11-17 the gateway sent the ARM stop after the last window, ARM answered 202, and `az aks show` showed `Stopped` about 2 minutes later. Before the fixes in section 9 it did not work, so check the power state after any change to the gateway.

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
| One window, start and stop included (about 24 minutes of node time) | about 0.04 |
| 100 windows | about 4 (2.60 for the 15 minutes alone) |
| Cluster left on for a month | 75.92 |

The node also runs during the start (about 7 minutes) and the stop (about 2 minutes), so one window costs about US$0.04 of node time (the number the site uses, `src/app/cost.ts` of the frontend). The 20-minute grace period after a deploy is extra. The budget itself is R$ 330 per month (about US$60); Azure bills this subscription in BRL: compare the numbers above with it at about R$ 5.50 per US dollar (2026-11-18).

The database can be stopped for 7 days at most with `az postgres flexible-server stop -g rg-consignado -n psql-consignado-km01`. Azure starts it again after that. Both hosts fail while it is stopped.

## 5. Rotate secrets

### Edge key

The key is made by Terraform (`random_password.edge_key`), stored in Key Vault as `edge-key`, and copied into the Worker as the secret `EDGE_KEY`. The first copy was made this way on 2026-11-17. A rotation was **(not executed)**. The gateway caches secrets for 5 minutes.

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

`apifn-host-key`: make a new host key on the API host function app, then set the Key Vault secret with the pipe of section 1. `turnstile-secret`: rotate it in the Cloudflare dashboard, then set the Key Vault secret. `hash-key`: made by Terraform (`random_password.hash_key`); the gateway only reads it. Do not rotate it without a plan. A new key makes all stored hashes of IP addresses and fingerprints useless for the duplicate check.

## 6. When the budget alert fires

1. **50 or 80 percent.** An email goes to the admin. The gateway writes an audit row only. Check `/admin`, "Engine and budget", and the cost view of the resource group. Look for a cluster that was left running.
2. **100 percent.** The action group calls the gateway webhook (`/api/hooks/budget`). The gateway pauses the engine (reason `budget`), ends the open windows and stops the cluster. The site and the serverless host continue. The webhook exists only while Terraform is applied with `TF_VAR_budget_webhook_url` (section 1).
3. Confirm the stop: `az aks show -g rg-consignado -n aks-consignado --query powerState.code`. If it is not `Stopped`, stop it by hand (section 3). Delivery of the alert and the webhook was not verified live, so do not rely on step 2 alone.
4. Find the cause before a resume: many windows, a cluster left on by a deploy, log ingestion, or the load balancer line.
5. Leave the engine paused until the next month, or resume it in `/admin` and accept the extra cost.

The alert is not a hard cap. The fixed costs of section 4 continue while the engine is paused. To stop all cost, delete the resources (section 8).

## 7. Remove a user's data on request

The gateway has no delete route. The admin does this by hand, with a data role on the storage account (the account accepts no key; Terraform does not give the admin a table data role, so assign one first). **(not executed)**

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

### Remove a test account

Test accounts are made by real sign-ups with a test mailbox. Remove each one when the test is over **(not executed)**:

1. In the Entra admin center, find the guest user by its email address and delete it. Note its object id first.
2. In table `users` of `stconsigappkm01`, delete the row with `PartitionKey` `u` and `RowKey` = that object id. Use an identity with `Storage Table Data Contributor` on the account; the account accepts no key.
3. Optional: delete its rows in `sessions` (column `userId`) and `audit`.
4. Rows that the test wrote to PostgreSQL stay: the API has no delete, and the data is synthetic.

### Fill an empty database with demo data

`deploy/seed/seed.sh` (branch `feat/seed` until it is merged) fills an empty API with synthetic data through the public endpoints: 4 agreements, 50 customers, 60 simulations, 30 contracts in all six states. It does not touch the database directly and deletes nothing. If agreement `DEMO-INSS` exists it prints `already seeded` and stops. The API has no public address, so run it as a one-off pod in the cluster during a window or after a deploy:

```bash
kubectl -n consignado run seed-$(date +%s) --rm -i --restart=Never \
  --image=curlimages/curl:8.11.1 --command -- \
  sh -s -- http://consignado-api.consignado.svc < deploy/seed/seed.sh
```

On 2026-11-17 it ran once with `az aks command invoke --file seed.sh`: about 250 calls in about 3 min 40 s, exit code 0. The script does not resume: after a partial run the guard says `already seeded`. Test it on a local database first (`docker compose up --build`).

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

## 9. Lessons from the first day

Real incidents of 2026-11-17, in the order they happened. Each one: symptom, cause, fix.

1. **Front Door refused.** Symptom: `terraform apply` failed on the Front Door profile with `Free Trial and Student account is forbidden for Azure Frontdoor resources`.
   Cause: the subscription offer. Fix: the edge is the Cloudflare Worker `consignado-edge` ([ADR 0010](../adr/0010-cloudflare-edge-instead-of-front-door.md)); the Front Door code stays behind `enable_front_door = false`.
2. **External ID tenant refused.** Symptom: a separate Entra External ID tenant could not be created.
   Cause: the region policy of the subscription. Fix: a B2B self-service sign-up user flow with an email one-time code in my own tenant, with guests at the most restricted level ([ADR 0011](../adr/0011-sign-up-in-the-workforce-tenant.md)).
3. **Tables could not be made by Terraform.** Symptom: `azurerm_storage_table` failed with "Key based authentication is not permitted on this storage account".
   Cause: the provider uses the account key for tables. Fix: the five tables are made by an ARM deployment (`consignado-tables`).
4. **Gateway answered 500 on every route.** Symptom: also `/api/health` failed.
   Cause: the gateway configuration wanted `ACS_SENDER`; Terraform sets `EMAIL_SENDER`, and the configuration was read at the first request of every route. Fix: the gateway reads `EMAIL_SENDER`, a missing sender means audit-only mail, and `/api/health` is a constant.
5. **Error text lost in the logs.** Symptom: the log lines had no exception text, which hid the cause of item 4.
   Cause: the Functions log handler does not fill `{0}` placeholders. Fix: every log line is built as plain text (exception class and message, 300 characters at most).
6. **Host storage 403 and "Failed to fetch host key".** Symptom: zip deploys were accepted, but the hosts could not read their storage, and the host key of the API host could not be listed.
   Cause: the Terraform provider adds `AzureWebJobsStorage` with an account key; the account refuses shared keys, and with identity-based settings that setting must not be used. Fix: `AzureWebJobsStorage = ""` plus `ignore_changes` on both function apps.
7. **Admin routes refused by the Functions host.** Symptom: the host would not load a route that starts with `admin` ("conflicts with one or more built in routes").
   Cause: `admin` is reserved by the Functions host. Fix: the route is `{area:regex(^admin$)}/{*path}`; the public URL `/api/admin/...` did not change.
8. **API build failed on CI runners.** Symptom: the image build and the API host build stopped on `wget: Failed to fetch apache-maven-3.9.11`, 3 times.
   Cause: the Maven wrapper downloads Maven at build time, and that download failed on the runners. Fix: the `Dockerfile` builds with the official Maven image; the API host workflow was started again.
9. **Deploy role did not fit the manifests.** Symptom: the deploy identity had `RBAC Writer` on the whole cluster, and `RBAC Writer` cannot change a `SecretProviderClass` (a custom resource).
   Cause: the first role design. Fix: `Azure Kubernetes Service RBAC Admin` on the namespace `consignado` only; the namespace is made once by the cluster admin (`WITH_NAMESPACE=1`, section 1).
10. **Contract creation would fail with 400.** Symptom: `POST /api/v1/contracts` needs `Idempotency-Key`, and the gateway proxy did not forward it.
    Cause: the header allow-list had five names without it. Fix: the gateway and the Worker forward `Idempotency-Key`; the end-to-end test then created contracts on both hosts.
11. **The cluster did not stop by itself (1).** Symptom: the engine stayed `running` with no window for 28 minutes; the log said `ARM stop cluster failed: IllegalArgumentException`.
    Cause: the code set the header `Content-Length`, which `java.net.http` refuses from the caller. No stop and no start was ever sent. Fix: the request sets only `Authorization` and `Accept`.
12. **The cluster did not stop by itself (2).** Symptom: after fix 11, ARM answered `HTTP 411` (Length Required).
    Cause: over HTTP/2 the Java client sends no `Content-Length` for an empty body. Fix: start and stop are sent over HTTP/1.1 with an empty byte body, so `Content-Length: 0` is sent. The automatic stop was then proven: 202, `stopping`, `cooldown`, cluster `Stopped`.
13. **A window waited 14 min 44 s on a running cluster.** Symptom: the session stayed at stage `control-plane`; the log had 207 lines `Kubernetes API GET: HTTP 403`; the page showed a false "cold start" of 14 min 44 s.
    Cause: the gateway asked first for `/api/v1/nodes`; its role `Azure Kubernetes Service RBAC Reader` cannot list nodes (cluster scope), and the code then treated the API server as down. Fix: pods and endpoints are read first; the node state comes from the pods. No role was changed. The page now says "Time to ready" and notes when the cluster was already running.
14. **Wrong `cold` badge.** Symptom: `activate` (4.6 s to 4.8 s) was marked cold, also on the AKS host.
    Cause: the rule was "upstream call over 3 s". Fix: only the Functions host can be cold, and only above 8 s. Why `activate` is slow is still open.
15. **Time estimates too low.** Symptom: the site said "about 8 to 15 s" and "about 9 s" for a serverless cold start; the measured value was 18.6 s to 18.9 s.
    Cause: the text came from local runs under emulation. Fix: the site says "about 15 to 25 s" cold and "0.3 to 1 s" warm.
16. **Guest snapshot empty after the seed.** Symptom: the masked table stayed empty for 32 minutes while the database had 50 customers.
    Cause: the first refresh ran before `apifn-host-key` existed; why it stayed empty for so long is not known. Fix: none needed; it filled at a later refresh.
17. **Budget webhook not in any file.** Symptom: none yet; it is a trap.
    Cause: the webhook URL holds a function key and is passed as `TF_VAR_budget_webhook_url`. Fix: set the variable before every plan or apply (section 1); otherwise the apply removes the webhook.
18. **Budget in the wrong currency.** Symptom (2026-11-18): `az consumption budget show` gave amount 60 and a spend of R$ 23.15 (39 percent on day 18); the engine would have paused itself within days.
    Cause: Azure keeps a budget in the billing currency, and this subscription is billed in BRL, so the "US$60" budget was R$ 60 (about US$11). The gateway also read the cost as `CostUSD` and the admin page printed BRL values as US$.
    Fix: budget R$ 330 (the US$60 I chose, at about R$ 5.50), gateway settings `BUDGET_LIMIT` and `BUDGET_CURRENCY`, cost query `Cost` with its `Currency` column, admin page in the currency the gateway sends.

Still open after the first day: a `DELETE` refused at the edge gets the Cloudflare HTML block page, not the JSON `{code, message}` of the contract; the Microsoft sign-up pages and the code mail show the tenant's display name, not the product name; the frontend cannot show the full CPF, because the API returns it masked to every caller.

## Example

A hypothetical case. The admin gets the 80 percent email on the 20th of the month. In `/admin` the engine shows `running` with no active session for two hours. The admin clicks "Stop the engine now", confirms `Stopped` with `az aks show`, then looks for the cause in the audit rows and the gateway logs before the next window is allowed.
