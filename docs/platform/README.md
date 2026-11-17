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
| Admin | The owner's account. | All of the above with no window limit, plus `/admin`: users, sessions, engine state, budget, contact requests. |

## Pages

| Page | Content |
|---|---|
| [architecture.md](architecture.md) | Diagrams: context, request paths, a serverless call, an access window, the engine states, identity and trust, the deploy pipelines. |
| [decisions.md](decisions.md) | One row per decision: why, the trade-off, what a bank would do. |
| [operations.md](operations.md) | The runbook: deploy, pause, stop, cost, key rotation, data removal, budget alert, delete. |
| [security-and-privacy.md](security-and-privacy.md) | Threats and controls, the personal data that is stored, the known gaps. |
| [business.md](business.md) | The product decisions: who it is for, the limits, the budget, what is not built. |
| ADRs | [0008 gateway](../adr/0008-gateway-in-front-of-two-hosts.md), [0009 cold cluster](../adr/0009-cold-cluster-and-access-windows.md), [0010 Cloudflare edge](../adr/0010-cloudflare-edge-instead-of-front-door.md), [0011 sign-up](../adr/0011-sign-up-in-the-workforce-tenant.md), [0012 budget and abuse](../adr/0012-budget-guard-and-abuse-limits.md). ADR 0001 to 0007 are about the API itself. |

## Status (2026-11-17)

This table is taken from the reports of the build. "Not verified" means: no report shows a live check. It does not mean "broken".

| Part | Status |
|---|---|
| Azure resources in `rg-consignado` (network, AKS, registry, Key Vault, PostgreSQL, storage, two function apps, identities, monitoring, email, budget) | **Live.** Created by Terraform. The last `terraform plan` showed no changes. |
| Azure Front Door and its WAF | **Not created.** The subscription refused it. The edge is a Cloudflare Worker. |
| Gateway function app accepts only Cloudflare address ranges | **Verified.** A direct call from another address got HTTP 403. |
| API host function app accepts only the gateway subnet | **Verified.** A direct call got HTTP 403. |
| Static site storage endpoint | **Verified.** It answered HTTP 200. |
| PostgreSQL server ready; database password resolved from Key Vault by the API host | **Verified.** |
| AKS settings: Azure RBAC on, local accounts off, workload identity on | **Verified.** |
| Sign-up user flow with email one-time code, linked to the site's app registration | **Configured.** Read in the portal in the same session. No reload check. |
| The Worker `consignado-edge` on the two host names | Not verified. |
| Gateway code deployed and answering through the edge | Not verified. |
| API on the serverless host, deployed and answering | Not verified. |
| API on the AKS host behind the internal load balancer | Not verified. |
| The three deploy workflows with GitHub OIDC login | Not verified. No report shows a run. |
| A full sign-up and sign-in from the site | Not verified. |
| A full access window: start, ready, countdown, stop, cool-down | Not verified. |
| Email to the owner; delivery of the budget alert; the budget webhook | Not verified. |
| Cold-start and latency numbers on Azure | Not verified. The numbers in these pages were measured locally under emulation. |
| Time that the cluster needs to start | Not verified. Not measured on this cluster. |

## History

The first version of the infrastructure was made by hand with the Azure CLI in a lab resource group: one AKS cluster with a public load balancer limited to one address, PostgreSQL, Key Vault and a deploy from GitHub Actions.
This platform replaces it with Terraform, a gateway, a second host and an internal load balancer.
