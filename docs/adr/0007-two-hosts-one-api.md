# ADR 0007: Two hosts, one API, and trust in the network

- Status: accepted
- Date: 2026-11-17

## Context

The same jar now runs on two Azure hosts: pods on AKS and an Azure Function with a custom handler. Both read and write the same PostgreSQL database. A gateway sits in front of both. It checks the Entra ID token, decides which host a request may use, and forwards only `GET`, `POST` and `PATCH` on `/api/v1/`.

Three kinds of caller exist: a guest with no token, a signed-in user, and the owner. The data is shared: every user sees the same agreements, customers and contracts.

That leaves three questions for this API. Who checks the caller? What may a guest do? What may a user change in data that belongs to everybody?

## Decision

**The API has no login of its own. It trusts the network.** Only the gateway can open a connection to it:

- On AKS the Service is an internal Azure load balancer with the fixed private IP `10.10.3.200` (`deploy/k8s/50-service.yaml`). It has no public address and no `loadBalancerSourceRanges`; the old `ALLOWED_CIDR` rule is gone.
- The function host accepts inbound traffic only from the gateway's subnet.

Authentication, sessions, quotas and the per-request trace live in the gateway. The API stays what ADR 0001 to 0006 describe: business rules, storage and events. It does not parse a token and does not know who the user is. The gateway sends `X-Consignado-User`; the API does not read it today.

**A guest gets one endpoint, and it cannot reach the database.** `POST /api/v1/simulations/preview` takes the principal, the term and the rate, and optionally an income and a margin percent, and returns the installment, the total, the interest and the margin check. `SimulationPreviewService` calls the same `PriceAmortization` and `MarginPolicy` as a stored simulation (ADR 0002), so the numbers are the same to the cent. It has no repository and no transaction. The inputs have the bounds of a stored simulation (R$ 100.00 to R$ 1,000,000.00, 1 to 420 months), so the largest schedule a guest can cause is 420 lines of `BigDecimal` arithmetic.

**Shared data is append-only, with one guarded change.**

- No `DELETE` exists and none is added. No free `PUT` or `PATCH` on a resource exists.
- Writes are creations (agreement, customer, simulation, contract) and the contract workflow of ADR 0003, where every step is a checked transition.
- The one exception is `PATCH /api/v1/customers/{id}/income`. It changes the net monthly income and nothing else. The new margin limit (income x the agreement's percent, rounded down) must still cover the installments of APPROVED and ACTIVE contracts; if not, the answer is 422 `income-below-committed-margin` with both numbers. The use case takes the same `select ... for update` on the customer row as an approval (ADR 0003), so an income cut and an approval cannot both pass against the old numbers.
- The listings (`GET /customers`, `/agreements`, `/simulations`, `/contracts`) are bounded: page size 1 to 100, page number up to 10,000, newest first. A simulation in a listing carries no schedule.

## Consequences

- One code base and one database give the same answer on both hosts. A request can go to the function host now and to AKS a minute later.
- The guest rule is enforced twice. The gateway lets a guest call only the preview route. If that rule had a bug, the preview still could not touch storage: `SimulationPreviewTest` runs it in a web slice whose context has no `DataSource`, no JPA and no repository, and an ArchUnit rule forbids `SimulationPreviewService` from depending on persistence, outbox, Spring Data or transactions.
- `CustomerIncomeIT` covers the guard: the boundary cent, proposals that reserve nothing, a settled contract that frees the margin, and an income cut racing an approval (exactly one of the two succeeds).
- A user cannot destroy what another user is looking at, because nothing can be deleted. The cost is that test data only grows; cleaning it is an operator task on the database, outside the API.
- The income change has no `If-Match`. Two changes at the same time are serialized by the row lock and the last one wins. That is acceptable for one number; the contract keeps its ETag.
- The network is a single line of defence. Anything that gets inside the virtual network, or a mistake in the function host's access rules, reaches an API that believes every caller. The CI deploy job checks that the Service holds the private IP, but nothing in this repository tests the function host's rule.
- The API cannot say who did what. The audit trail is the gateway's.

## What a bank would add

- **Service-to-service authentication.** The gateway would prove its identity on every call (mTLS in a mesh, or a managed-identity token that the API validates as an OAuth2 resource server), so a network mistake alone is not enough. The API would then also check roles: an operator approves, a customer only simulates.
- **Per-tenant data.** A tenant or owner column on every row, filtered in every query (and row-level security in PostgreSQL), instead of one shared data set.
- **An audit log inside the API** for the income change and every workflow step: who, when, old and new value. Today only the contract's status changes are recorded, as outbox events.
- **Rate limits per caller** on the preview, inside the API or the gateway. Here the WAF on Front Door is the only protection in front of it.
