# ADR 0003: Contract state machine and concurrency control

- Status: accepted
- Date: 2026-11-13

## Context

A contract moves through `SIMULATED -> PROPOSED -> APPROVED/REJECTED -> ACTIVE -> SETTLED/CANCELLED`. Two kinds of races matter:

1. Two operators (or a retry and an operator) change the same contract at the same time.
2. Two different contracts of the same customer get approved at the same time. Each one fits in the margin alone, but not both together. This is a write skew: neither transaction changes a row the other one reads.

## Decision

**State machine.** `ContractStatus` keeps every allowed edge in one `EnumMap`. Anything not listed is rejected with `IllegalStatusTransitionException` (HTTP 409 with `currentStatus` and `requestedStatus`). Allowed edges:

| From | To |
|---|---|
| SIMULATED | PROPOSED |
| PROPOSED | APPROVED, REJECTED, CANCELLED |
| APPROVED | ACTIVE, CANCELLED |
| ACTIVE | SETTLED, CANCELLED |
| REJECTED, SETTLED, CANCELLED | none (terminal) |

`SIMULATED` exists as a state of the machine, but a contract row is created directly in `PROPOSED` (the simulation row plays the SIMULATED role). Only `APPROVED` and `ACTIVE` reserve margin.

**Same contract, concurrent writers: optimistic locking.** `LoanContract` has a JPA `@Version`. The version is also the HTTP `ETag`. A client can send `If-Match` on a state change: a stale value gets 412 before anything happens. Without `If-Match`, a lost race still fails at flush with an optimistic lock error, mapped to 409 `concurrent-update`.

**Same customer, different contracts: pessimistic lock on the customer row.** `approve` runs `select ... for update` on the customer, then sums the installments of APPROVED and ACTIVE contracts, then decides. Two approvals for one customer are serialized; approvals for different customers are not affected.

## Alternatives I did not take

- `SERIALIZABLE` isolation for approvals: correct, but the application must retry on serialization failures, and it affects more than this one rule.
- A `committed_margin` column on the customer, updated with optimistic locking: works, but duplicates data that can drift from the contracts.
- A database constraint: PostgreSQL cannot express "sum over rows below a value" as a constraint.

## Consequences

- `ContractStatusTest` checks all 49 status pairs against an independent table.
- `ContractLifecycleIT.concurrentApprovalsForTheSameCustomerCannotBothConsumeTheMargin` fires two approvals at once and expects exactly one 200 and one 422.
- The lock is held only for the approval transaction, which does a few indexed reads and one update.
