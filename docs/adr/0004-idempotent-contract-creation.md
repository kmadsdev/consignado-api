# ADR 0004: Idempotency key on contract creation

- Status: accepted
- Date: 2026-11-13

## Context

`POST /api/v1/contracts` creates a loan proposal. Clients retry on timeouts, and a mobile client may send the same request twice. A duplicate proposal for the same customer is a real problem in lending.

## Decision

- The client must send an `Idempotency-Key` header (8 to 64 characters of `[A-Za-z0-9_-]`; a UUID fits).
- The key is stored on the contract row with a unique constraint (`uk_contract_idempotency_key`). A second unique constraint (`uk_contract_simulation`) makes sure a simulation becomes at most one contract.
- Flow in `ContractService.propose`:
  1. If a contract with this key exists and has the same `simulationId`, return it with **200** (first call returned 201).
  2. If it exists with a different `simulationId`, return **422** `idempotency-key-reused`.
  3. Otherwise insert in its own transaction and return **201**.
  4. If the insert fails on a unique constraint, or the "simulation already used" check fires, another request won the race. Read the winner's row by key and go to step 1. If there is no row with this key, the simulation was used by a different key: **409** `simulation-already-used`.
- The database is the arbiter. There is no in-memory lock and no separate idempotency table.

## Consequences

- `ContractLifecycleIT.concurrentRequestsWithOneKeyCreateExactlyOneContract` sends 8 requests with one key at the same time and expects one 201, seven 200 and one row.
- The replay returns the contract as it is now, not a byte copy of the first response. If the contract was approved in between, the replay shows APPROVED. I think that is the more useful behaviour for a client that lost the first response.
- Keys do not expire. A real system would expire them after some days, or keep them in a separate table with a TTL.
- Other POST endpoints (agreements, customers, simulations) are not idempotent. Agreements and customers have natural unique keys (code, CPF), so a retry gets 409. A repeated simulation only creates another quote.
