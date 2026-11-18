# ADR 0012: A budget guard and limits against abuse

- Status: accepted
- Date: 2026-11-17
- Updated: 2026-11-17, after the first live day

## Context

The platform is public and anyone can sign up. It runs on a student subscription with a budget of R$ 330 per month (about US$60); Azure bills this subscription in BRL. The largest variable cost is the AKS node, and any signed-in user can start it.

Two risks follow. One person makes many accounts to get more than 3 windows. Or normal use, a mistake or an attack spends the month's budget.

The platform stores personal data only to limit abuse, so the limits must work with as little data as possible.

## Decision

**Limits per account**

- 3 access windows of 15 minutes per account. The admin can grant more after a request through the contact form.
- A per-user guard in the gateway: 5 session requests and 3 contact requests in 10 minutes.

**The duplicate-account rule**

- When a user completes the profile, the gateway compares the device fingerprint and the IP address with the other accounts.
- **Same device fingerprint: the new account is held.** The admin approves or blocks it in `/admin`. An approved account is not held again.
- **Same IP address only: a flag, and the account stays active.** An office or a university shares one address.
- The gateway stores the IP address and the fingerprint only as HMAC-SHA256 with a key from Key Vault. The client address comes only from the header that Cloudflare sets, and only after the edge key matched.

**Bot check**

- The contact form and the profile form carry a Cloudflare Turnstile token. The gateway verifies it. If Cloudflare does not answer, the form is refused. If no Turnstile secret is configured, the form is accepted and a log line says `bot check not configured`.

**The budget guard**

- A subscription budget, `budget-consignado`: R$ 330 per month (about US$60); Azure bills this subscription in BRL (the amount is in the billing currency; corrected on 2026-11-18, it was 60 taken as dollars, so the real limit was R$ 60). Alerts at 50, 80 and 100 percent go to me by email.
- At 100 percent an action group calls `POST /api/hooks/budget` on the gateway, with a function key, through the edge (this route does not need the edge key). The URL holds the key, so it is not in a file: Terraform reads it from `TF_VAR_budget_webhook_url`. An apply without that variable removes the webhook. The gateway reads the spend and the budget from the payload. Spend at or above the budget, or a payload that cannot be read, pauses the engine (reason `budget`), ends the open windows and stops the cluster. An earlier alert writes an audit row only.
- While the engine is paused, new windows are refused. The site and the serverless host continue.
- The admin can pause, resume and stop the engine by hand in `/admin`.

## Consequences

- **The budget guard is not a hard cap.** An Azure budget sends alerts; it stops nothing by itself. The alert comes after the spend is recorded. The fixed costs (database, registry, node disk, public IP; about US$31 to 35, about R$ 170 to 195, per month) continue while the engine is paused. The guard removes only the largest variable cost.
- The serverless host has no budget guard of its own. Its protection is the edge, the token rule and the low scale-out limit of the function apps (gateway 2 instances, API host 1 instance).
- The duplicate check is a filter, not a proof. A new browser or a VPN passes it. The hashes match only exact values.
- Held accounts need the admin's attention. A real person on a shared device waits for an approval.
- The per-user guard counts in memory per instance. It stops a loop of one user, not a distributed attack.
- A failed bot check configuration is open, not closed: until the secret exists there is no bot check. This was chosen so that the platform can start before the widget exists.
- Verified live: the Turnstile check (a visible browser passes, a headless browser is refused).
- Not verified at the time of writing: the delivery of the budget alert, the payload fields of the webhook, a pause from a real alert, the email to the admin, and the duplicate-account hold with two real accounts.

## What a bank would add

- **Verified identity** before any resource is given, and device intelligence from a fraud product in place of a hashed fingerprint.
- **Rate limits in a shared store**, per user and per address, and a managed bot protection service.
- **Cost controls by design**: quotas per tenant, capacity paid in advance, anomaly alerts. Production is not stopped by a budget alert.
- **A data protection review** of the abuse data: purpose, retention and the legitimate-interest assessment in writing.
