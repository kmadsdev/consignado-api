# Consignado platform

One Spring Boot API (`consignado-api`) runs on two Azure hosts: an Azure Function with a custom handler, and pods on an AKS cluster.
A Java gateway is in front of both hosts. It checks the caller, applies the limits, and returns a trace of each request.
A static React site shows that trace as a live architecture diagram.
The AKS cluster is stopped most of the time. A signed-in user starts it for a 15-minute access window.
Terraform creates the Azure resources. A Cloudflare Worker is the public edge.

The data is synthetic. This is a technical demonstration, not a credit offer.

## Repositories

| Repository | What it holds |
|---|---|
| [`kmadsdev/consignado-api`](https://github.com/kmadsdev/consignado-api) | The Spring Boot API, the Kubernetes manifests (`deploy/k8s`), the Terraform code (`infra/terraform`), these documents and the ADRs. This is the main documentation entry. |
| [`kmadsdev/consignado-serverless`](https://github.com/kmadsdev/consignado-serverless) | `gateway/`: the Java gateway (Azure Functions). `api-host/`: the package that runs the unchanged API jar as an Azure Functions custom handler. |
| [`kmadsdev/consignado-front`](https://github.com/kmadsdev/consignado-front) | The React and Cloudscape frontend, and `edge/`: the Cloudflare Worker `consignado-edge`. |

## Addresses

| Address | What |
|---|---|
| `https://consignado.kmads.dev` | The site and the gateway API (`/api/*`). |
| `https://cluster.consignado.kmads.dev/app/<id>/aks/` | The page of one access window on the Kubernetes host. `<id>` is the session id. |

Both host names go to the same Worker.

## Access levels

| Level | How | What it can do |
|---|---|---|
| Guest | No token. | One API route: the stateless loan preview on the serverless host (`POST /api/fn/api/v1/simulations/preview`), and the public gateway routes (health, engine state, masked snapshot, contact form). Never the AKS host. Never the database. |
| User | Microsoft Entra ID token. | The serverless host at all times. The AKS host only inside an own access window: 15 minutes, 3 windows per account. Reads all shared data. Writes only through the workflow endpoints. |
| Admin | My own account. | All of the above with no window limit, plus `/admin`: users, sessions, engine state, budget, contact requests. The admin is matched by the object id in the token. |

## Pages

| Page | Content |
|---|---|
| [architecture.md](architecture.md) | Diagrams: context, request paths, a serverless call, an access window, the engine states, identity and trust, the deploy pipelines. |
| [decisions.md](decisions.md) | One row per decision: why, the trade-off, what a bank would do. |
| [operations.md](operations.md) | The runbook: deploy, pause, stop, cost, key rotation, data removal, budget alert, delete. |
| [security-and-privacy.md](security-and-privacy.md) | Threats and controls, the personal data that is stored, the known gaps. |
| [business.md](business.md) | The product decisions: who it is for, the limits, the budget, what is not built. |
| ADRs | [0008 gateway](../adr/0008-gateway-in-front-of-two-hosts.md), [0009 cold cluster](../adr/0009-cold-cluster-and-access-windows.md), [0010 Cloudflare edge](../adr/0010-cloudflare-edge-instead-of-front-door.md), [0011 sign-up](../adr/0011-sign-up-in-the-workforce-tenant.md), [0012 budget and abuse](../adr/0012-budget-guard-and-abuse-limits.md). ADR 0001 to 0007 are about the API itself. |

## Status (2026-11-17, first day live)

The platform went live on 2026-11-17. This table holds what was checked live on that day, by whom and how.
"Not verified" means that no live check exists. It does not mean "broken".
Times are in the browser of the tester unless the row says otherwise.

| Part | Status |
|---|---|
| Azure resources in `rg-consignado` (network, AKS, registry, Key Vault, PostgreSQL, storage, two function apps, identities, monitoring, email, budget) | **Live.** Created by Terraform. The last full `terraform plan` showed no changes. |
| Azure Front Door and its WAF | **Not created.** The subscription refused it: `Free Trial and Student account is forbidden for Azure Frontdoor resources`. |
| Edge: Cloudflare Worker `consignado-edge` on `consignado.kmads.dev` and `cluster.consignado.kmads.dev` | **Live, verified.** Both host names answer with a valid certificate. The site and `/api/*` pass through the Worker. |
| Cloudflare zone rules, scoped to the two host names | **Live.** A rate limit of 40 requests per 10 seconds per IP address on `/api/`; a method allow-list; a managed challenge for non-browser clients on `/api/contact` and `/api/me/profile`. Verified in the end-to-end test: a `DELETE` is refused at the edge with 403. |
| Gateway function app accepts only Cloudflare address ranges plus the edge key | **Verified.** A direct call from another address gets 403. |
| API host function app accepts only the gateway subnet | **Verified.** A direct call gets 403. |
| Gateway through the edge | **Live, verified.** Health, engine state, masked snapshot, 401 without a token, guest preview. |
| API on the serverless host | **Live, verified.** All operations through the site passed in the end-to-end test (9 calls, 0 failures). |
| API on the AKS host behind the internal load balancer | **Live, verified.** Deployed by the CI job (OIDC, registry push, Azure RBAC, smoke test). All operations inside a real access window passed (10 calls, 0 failures). |
| Deploy workflows | API (`ci.yml`, job `deploy`), gateway and site: green runs on 2026-11-17. API host workflow: both runs on 2026-11-17 ended with the deploy step marked failed (the zip was accepted, then the host key could not be fetched: the host storage fault of [lesson 6](operations.md#9-lessons-from-the-first-day)). The host serves live calls. A fully green run is not on record at the time of writing. |
| Sign-up with an email one-time code, then sign-in | **Verified.** A real sign-up with a test mailbox passed: code mail in less than 5 s, then back on the site. |
| Profile form with Turnstile | **Verified** in a visible browser. A headless browser is refused, as expected. |
| Access rules | **Verified.** A user gets 403 on admin routes, 403 `no-session` on the AKS host without a ready window, 404 for another user's session, 405 for `PUT` on the proxy. |
| Access window on a running cluster | **Verified** after a fix. The first window waited 14 min 44 s because of a gateway defect (see [Lessons](operations.md#9-lessons-from-the-first-day)). It became ready at the first observation after the fix was deployed. |
| Automatic cluster stop after the last window | **Verified.** ARM accepted the stop (HTTP 202); the engine went to `stopping`, then `cooldown`; the cluster showed `Stopped` about 2 minutes later. A new window was refused with 409 `cooldown` until 15 minutes after the stop. |
| Seed data in the database | **Done.** 4 agreements, 50 customers, 60 simulations, 30 contracts, all synthetic, made through the API by `deploy/seed/seed.sh` (branch `feat/seed`; not merged into `main` at the time of writing). The guest snapshot shows the rows masked. |
| Real cold start of the AKS cluster from `Stopped` | **Verified** on 2026-11-17: 7 min 13 s from the request to the first answer (control plane 4 min 26 s, node 2 min 13 s, Spring Boot and database 26 s, load balancer 8 s). A window on a running cluster is ready in under 2 s. The automatic stop after the window worked. |
| Admin pages (`/admin`) with a real admin token | **Verified** on 2026-11-17 with a real token: users, grant of windows, sessions, contact requests, pause and resume, stop of the engine. |
| Email to the admin when a new user signs up | Not verified. |
| Duplicate-account hold with two real accounts | Not verified. The rule is tested with fakes only. |
| Budget pause from a real budget alert | Not verified. The webhook is attached to the action group (see [operations](operations.md#6-when-the-budget-alert-fires)). |

### Measured times

| What | Time | How it was measured |
|---|---|---|
| Serverless host, cold call | 18.6 s to 18.9 s (2 of 2 calls) | Browser time of the end-to-end test, through the edge. The gateway trace put 18.6 s in the stage `function-cold-start`. The second cold call came 17 minutes after the last call to the host. |
| Serverless host, warm call | 0.25 s (guest preview) to 0.9 s (median of 11 signed-in calls; range 0.55 s to 1.2 s) | Browser time, same test. Gateway trace `totalMs` median 572 ms. |
| AKS host, warm call | about 0.76 s (median of 12 calls; range 0.50 s to 1.1 s) | Browser time inside an access window. Gateway trace `totalMs` median 519 ms. |
| `POST .../contracts/{id}/activate` | 4.6 s to 4.8 s on both hosts | Browser time. The other writes take 0.9 s to 1.2 s. The cause is not known yet. |
| Cluster stop | about 2 minutes | From the accepted ARM stop to `Stopped / Succeeded` in `az aks show`. |
| Cluster start from `Stopped` | 7 min 13 s (control plane 4 min 26 s, node 2 min 13 s, Spring Boot and database 26 s, load balancer 8 s) | One measured run on 2026-11-17, from the window request to the first answer. A window on a running cluster: under 2 s. |

Local numbers from before the deploy (Functions host image under emulation: cold start 11.2 s to 12.0 s) are kept in the `api-host` README of `consignado-serverless`. They were too low: they do not include instance allocation and package mount on Azure.

## History

The first version of the infrastructure was made by hand with the Azure CLI in a lab resource group: one AKS cluster with a public load balancer limited to one address, PostgreSQL, Key Vault and a deploy from GitHub Actions.
This platform replaces it with Terraform, a gateway, a second host and an internal load balancer.
