# ADR 0010: A Cloudflare Worker as the edge, not Azure Front Door

- Status: accepted
- Date: 2026-11-17

## Context

The first design had Azure Front Door Standard as the one public entry: a CDN for the site, managed certificates for two custom domains, a rule engine for the single-page-application rewrite and the security headers, and a WAF with rate limits. The gateway would accept only the Front Door service tag and the id header of this profile.

Terraform could not create it. Azure answered:

`Free Trial and Student account is forbidden for Azure Frontdoor resources.`

The other Azure options did not fit. The classic Azure CDN cannot be created any more. Blob Storage alone cannot serve a custom domain over HTTPS. Front Door Standard also costs US$35 per month, which would have put the fixed cost at about US$66 to 70, over the US$60 budget.

The DNS zone of the domain was already at Cloudflare.

## Decision

**The edge is one Cloudflare Worker, `consignado-edge`,** on `consignado.kmads.dev` and `cluster.consignado.kmads.dev`. Its code is in `kmadsdev/consignado-front`, folder `edge/`.

- `/api/*` goes to the gateway. It is never cached. Only `GET`, `POST`, `PUT` and `PATCH` pass, and a body larger than 64 KB is refused. The Worker forwards five request headers and drops the rest.
- Every other path goes to the static website in Blob Storage. A path with no file extension is served as `index.html`. Files under `/assets/` are cached for one year.
- The Worker sets the security headers: HSTS, a content security policy, no framing, no content sniffing.
- The Worker takes no access decision and holds no user data. Tokens and quotas are checked by the gateway.

**Two controls replace the Front Door origin lock.**

1. The gateway function app accepts only the Cloudflare IPv4 ranges (15 rules, default deny).
2. Those ranges are shared by all Cloudflare customers, so the Worker also sends a shared key in the header `X-Edge-Key`. The gateway compares it in constant time with the Key Vault secret `edge-key` and answers 403 with no detail when it does not match. The client address is read only from `CF-Connecting-IP`, and only after the key matched.

The Front Door code stays in `infra/terraform/modules/edge` behind the flag `enable_front_door` (default `false`), for a subscription that allows it.

## Consequences

- The edge costs nothing (free plan, 100 000 requests per day) and gives TLS for both host names, a cache and network-level DDoS protection.
- One part of the request path is not Azure. The story "everything on Azure" is no longer true, and the page says so.
- **No managed WAF rule set.** The free plan gives custom rules and rate limits, not the managed rule sets. Which rules are configured in the Cloudflare zone is not recorded in the reports.
- The edge key is a shared secret, the only one that leaves Azure. A rotation must change Key Vault and the Worker together; the gateway caches the key for 5 minutes.
- The Cloudflare address list is a Terraform variable. When Cloudflare changes the list, someone must update it by hand.
- The Worker is deployed by hand, because a Cloudflare token is not stored in the repository.
- Verified live: a direct call to the gateway from an address that is not Cloudflare gets 403. Not verified when this ADR was written: the Worker on the two host names, and a full request through it.

## What a bank would add

- **An edge with a managed WAF** (Front Door Premium or an equal product), with managed rule sets, bot protection and logs sent to a security team.
- **A private link from the edge to the origin** in place of an address list and a shared key.
- **Infrastructure as code and a pipeline for the edge** too, with the token in a protected environment.
