# ADR 0008: A gateway in front of the two hosts

- Status: accepted
- Date: 2026-11-17
- Updated: 2026-11-17, after the first live day

## Context

ADR 0007 says that the API has no login of its own and trusts the network. Something else must then answer four questions on every request: who is the caller, which host may the caller use, how much may the caller use, and what happened to the request on its way.

The platform has two hosts for the same jar (an Azure Function and pods on AKS). The frontend draws the real path of each request, so it needs measured stages, not guesses. The cluster is stopped most of the time, so something must also start and stop it.

## Decision

**One Java function app, `func-consignado-gw-km01`, is the only public backend.** Its code is in `kmadsdev/consignado-serverless`, folder `gateway/`.

- **Routing.** `/api/fn/...` goes to the API on Functions. `/api/aks/...` goes to the internal load balancer of AKS. Only paths under `api/v1/`, only `GET`, `POST` and `PATCH`, bodies up to 64 KB, and an allow-list of six request headers go upstream. `Authorization` and cookies never go upstream.
- **Identity.** The gateway verifies the Entra ID token itself: signature against the tenant's keys, issuer, tenant, audience, `exp`, `nbf` and the scope `access_as_user`. A token that does not verify gives 401; it is never treated as a guest.
- **Access rules.** A guest may call one proxied route, the stateless preview. A user may call the Functions host at all times and the AKS host only with an own window in state `ready`. The admin is the owner's account.
- **Trace.** Each proxied answer carries `x-consignado-host` and `x-consignado-trace`: the stages of the request with measured milliseconds, and `cold=true` only for the Functions host and only when the upstream call took more than 8 seconds. The first rule (3 seconds, both hosts) marked slow business calls as cold starts.
- **Engine.** The gateway starts and stops the cluster for access windows (ADR 0009) and holds the budget guard (ADR 0012).
- **Structure.** Plain Java, no Spring. The rules are in `core/` with no Azure type and an injected clock. Azure is behind interfaces in `ports/`; the implementations are in `adapters/`. All objects are built once in `App.java`.
- **State.** Users, sessions, engine state, contact requests and audit rows are in Azure Table Storage, reached with a managed identity.

The API hosts accept the gateway only: the function host by a subnet rule plus its host key from Key Vault, the AKS Service by having only a private address in the virtual network that the gateway is integrated with.

## Consequences

- The API did not change to get authentication, quotas or tracing. It stays what ADR 0001 to 0007 describe.
- The rules are tested with no network: 82 tests run the real core and the real handlers against in-memory fakes of every port. Line coverage of `core` and `http` is about 96%.
- The adapters are thin, and they were not run against real Azure during the build. The first live run found faults that no fake could show: a setting name, the log text, a route name reserved by the Functions host, the headers and HTTP version of the ARM calls, and a node list that the gateway role cannot read. All are fixed; the [runbook](../platform/operations.md#9-lessons-from-the-first-day) lists them.
- The gateway is a single point of failure and a JVM function with its own cold start. Its first calls in the end-to-end test took 3.0 s to 3.9 s; warm calls 0.2 s to 0.9 s.
- Table Storage has no transactions across rows. Session rows use the ETag. The engine row does not: a request and the timer can overwrite each other, and the next timer run (one minute) corrects the state.
- The per-user request guard is in memory per instance. With more than one instance, each counts alone. The real rate limit is at the edge.
- A new header that the API needs must be added to the allow-list.

## What a bank would add

- **A managed API gateway** (rate limits, quotas, developer keys, policies) in place of hand-written routing, with the custom rules as a small service behind it.
- **Service-to-service authentication** from the gateway to the API, so the network is not the only control (see ADR 0007).
- **Distributed tracing** with a standard format (OpenTelemetry) across the edge, the gateway and the API, in place of a trace header that the gateway builds.
- **A store with transactions** for sessions and engine state, and a distributed rate limiter.
