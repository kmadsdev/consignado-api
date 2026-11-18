# Security and privacy

What can go wrong, what stops it, what personal data is stored, and what is still open.
This page is written by the builder of the platform. It is not a legal opinion and not the result of an external security test.

Back to the [entry page](README.md).

## Threats and controls

### Edge

| Threat | Control |
|---|---|
| Traffic in clear text | The Worker redirects HTTP to HTTPS and sets HSTS. Cloudflare holds the TLS certificates of both host names. |
| Script injection or framing of the site | The Worker sets a content security policy (own origin, Turnstile and the Entra ID sign-in only), `X-Frame-Options: DENY`, `nosniff` and a strict referrer policy. |
| Large or unusual requests to the API | A zone rule refuses other methods before the Worker runs (verified: a `DELETE` gets 403). The Worker accepts only `GET`, `POST`, `PUT` and `PATCH` on `/api`, refuses a declared body above 64 KB, forwards six request headers and drops all others. It removes cookies from API answers. |
| Cached private data | `/api` answers are never cached (`no-store`). |
| Volumetric attack and scripts | Cloudflare network-level protection. Zone rules scoped to the two host names: a rate limit of 40 requests per 10 seconds per IP address on `/api/`, and a managed challenge for non-browser clients on `/api/contact` and `/api/me/profile`. |

### Gateway

| Threat | Control |
|---|---|
| A call around the edge, direct to the function app | The function app accepts only Cloudflare address ranges (default deny; verified: 403 from another address). The gateway also requires the edge key, compared in constant time; a refusal is a 403 with no detail and no work done. |
| A forged client address | The gateway reads the client address only from the header that Cloudflare sets, and only after the edge key matched. |
| A direct call to an API host | The API host function accepts only the gateway subnet (verified: 403) and needs a host key. The AKS Service has only a private address. |
| Path or method tricks through the proxy | Only paths under `api/v1/`, only `GET`, `POST`, `PATCH`, bodies up to 64 KB, an allow-list of six request headers (`accept`, `accept-language`, `content-type`, `idempotency-key`, `if-none-match`, `x-request-id`). `Authorization` and cookies never go upstream. Verified live: `PUT` gets 405, a path outside `api/v1/` gets 404. |
| Information in error messages | Errors are `{code, message}` with no stack trace and no internal name. Logs hold the exception class and message, cut to 300 characters; they never go to the caller. |
| The gateway damages the cluster | Its Azure role allows read, start and stop only. The code has no delete or update call. |

### Tokens

| Threat | Control |
|---|---|
| A forged or expired token | The gateway verifies the signature against the tenant's keys, and the issuer, tenant, audience, `exp`, `nbf` and scope. An unsigned token is refused. |
| A bad token treated as a guest | A token that does not verify gives 401. It never falls back to guest access. |
| A removed signing key still accepted | The key set is cached for 1 hour, so a removed key is accepted for that time at most. Accepted risk. |
| A user reads another user's window | A session can be read by its owner or the admin only. Another user gets 404. The AKS proxy needs the caller's own window in state `ready`. |
| Admin routes | The admin is one account, matched by the object id in the token. Every admin call writes an audit row. |

### Quotas and abuse

| Threat | Control |
|---|---|
| One person uses the cluster without limit | 3 windows of 15 minutes per account. A window is counted when it opens. |
| Many accounts for one person | Same device fingerprint: the account is held for approval. Same IP only: a flag. A filter, not a proof: a new browser or a VPN passes it. |
| Scripts on the forms | Turnstile token on the contact form and the profile form, verified by the gateway (verified live). If no secret is configured, the check is skipped and logged. |
| A loop from one user | 5 session requests and 3 contact requests per user in 10 minutes. In memory per instance. |
| Start and stop cycles that Azure refuses | 15-minute cool-down after each stop. |
| The budget is spent | Alerts at 50, 80, 100 percent. At 100 percent the engine pauses and the cluster stops. Not a hard cap: see [ADR 0012](../adr/0012-budget-guard-and-abuse-limits.md). |
| A guest reaches the data | The gateway lets a guest call one route. That endpoint has no repository in the API code, and a test proves it. |
| A user destroys shared data | The API has no `DELETE` and no free update. The one guarded change (income) is checked against committed installments under a row lock. |

### Secrets

| Threat | Control |
|---|---|
| A password in a repository or in GitHub | None exists. Deploys use GitHub OIDC. Azure calls use managed identities. App registrations have no client secret. The registry has no admin user. Storage accepts no account key. |
| A leaked secret from Key Vault | Key Vault is in RBAC mode; each workload identity has a read-only role. Secrets: `db-password`, `edge-key`, `apifn-host-key`, `turnstile-secret`, `hash-key`. |
| A secret in Terraform output or logs | No secret is an output. `outputs.json` holds names and non-secret values. The database password exists in the remote state; the state storage has no public access, no shared key and Entra ID sign-in only. |
| A leaked kubeconfig | No kubeconfig with a client certificate exists: local accounts are off. |

### Network

| Threat | Control |
|---|---|
| A public entry to the cluster | The AKS Service is an internal load balancer. The gateway reaches it through the virtual network. |
| A public entry to the API host function | Inbound from the gateway subnet only. |
| The database is reached from the internet | TLS required. The firewall rule admits only addresses of Azure. See "Known gaps". |

### Data

| Threat | Control |
|---|---|
| A leak of the gateway tables shows addresses | IP addresses and fingerprints are stored only as HMAC-SHA256 with a key from Key Vault. |
| Personal data in the public snapshot | The snapshot masks names and CPF and removes email, phone, address and birth date. Salary values stay. The loan data is synthetic. |
| CPF in logs | The API never logs a CPF ([ADR 0006](../adr/0006-observability.md)). |

## Personal data (LGPD view)

The loan data (customers, agreements, contracts) is synthetic. The personal data below is about the visitors of the platform.

| Data | Where | Why | Legal basis in the notice | How long |
|---|---|---|---|---|
| Email, name, company, LinkedIn URL, consent time | Table `users` in `stconsigappkm01` (Azure, `belgiumcentral`) | To make the account and to know who uses the demonstration | Consent | Until the person asks for deletion, or 12 months without use (the notice says so; no job enforces it yet) |
| Guest user object (email, display name) | My own Entra ID tenant | Sign-in | Consent | Until deleted by the admin |
| Hash of the IP address, hash of the device fingerprint, flags | Table `users` | Abuse prevention: one person, one trial | Legitimate interest | With the account |
| Access windows: requested, ready, ended, events | Table `sessions` | To apply the window limit and to control cost | Not stated in the notice | Not stated in the sources |
| Contact messages with name and email | Table `contacts` | To answer the person | Consent on the form | Not stated in the sources |
| Audit rows: who, action, target, IP hash | Table `audit` | Security log | Legitimate interest | 90 days by the notice; not enforced yet |
| Request and application logs | Log Analytics and Application Insights | Operation and security | Legitimate interest | 30 days (workspace setting) |
| IP address in transit | Cloudflare edge | Firewall and rate limits | Legitimate interest | Controlled by Cloudflare |

- **Guests.** What a guest types stays in the browser. No account is made and nothing goes to the database.
- **Processors.** Microsoft Azure (hosting, database, sign-in, logs, email) and Cloudflare (DNS, the edge in front of all traffic, the bot check). The Azure resources are in `belgiumcentral`, with the database in `northcentralus` and email data in Europe. All are outside Brazil.
- **Rights.** A person uses the contact form on the site, from the email address of the account. The admin answers by email. The removal steps are in the [runbook](operations.md).
- **Notice.** The privacy notice and the terms are pages of the site (`/privacy`, `/terms`).

## Known gaps

These are stated on purpose. None is hidden by the design.

1. **No managed WAF rule set.** The edge is on the Cloudflare free plan. Front Door, with its WAF, was refused by the subscription. The gateway's own input rules are the main filter.
2. **The database has a public endpoint.** The firewall rule admits every address of Azure, in any tenant. The password and TLS are the real controls. I chose this for cost: a private endpoint costs about US$8 per month more.
3. **Guests are in the same tenant as the subscription.** A separate External ID tenant was refused. Guest permissions are at the most restricted level, but the separation is weaker than a tenant of their own.
4. **The privacy notice was not reviewed by a lawyer.** It names Cloudflare as the edge for all traffic.
5. **Audit rows are not deleted after 90 days.** The notice promises 90 days. The gateway deletes no row. A storage rule or a job must be added. The same is true for the "12 months without use" rule of accounts.
6. **The API trusts the network.** It has no authentication of its own ([ADR 0007](../adr/0007-two-hosts-one-api.md)).
7. **One shared secret with the edge.** The edge key is copied by hand and rotated by hand.
8. **Limits in memory.** The per-user guard and the secret cache are per instance. The engine row has no optimistic lock.
9. **The bot check fails open** when its secret is not configured.
10. **No scheduled rebuild and no vulnerability scan** of the serverless package, which carries its own Java runtime.
11. **The Microsoft sign-up pages show the tenant, not the product.** The permission page and the code mail carry the tenant's display name and domain. These are tenant settings.
12. **Some live behaviour was not verified** when this page was written: the cold start from a stopped cluster, the admin pages with a real token, the admin email, the duplicate-account hold with two real accounts, the budget pause from a real alert. The list is in the status table of the [entry page](README.md).

## Example

A hypothetical case. Someone finds the host name of the gateway function app in a public repository and calls it directly, with a copied header `CF-Connecting-IP` to hide the origin. The call comes from an address that is not Cloudflare, so the platform refuses it before any code runs. If the call came from another Cloudflare Worker, the address would pass, but the edge key would be missing: the gateway answers 403 and never reads the address header. The names in a public repository are not a secret, and the design does not depend on them.
