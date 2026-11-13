# ADR 0001: Layered packages with a pure domain

- Status: accepted
- Date: 2026-11-13

## Context

The rules that matter most in this service are arithmetic and state rules: CPF check digits, the Price schedule, the margin cap and the contract lifecycle. I want to test them in milliseconds, with no Spring context and no database, and I want them to stay that way as the code grows.

A full hexagonal split (domain model, separate JPA model, mappers in both directions) would double the number of classes for a service this size.

## Decision

Six packages under `io.github.kmadsdev.consignado`:

| Package | Holds | May depend on |
|---|---|---|
| `domain` | `Cpf`, `Money`, `PriceAmortization`, `MarginPolicy`, `ContractStatus`, domain exceptions | `java.*` only |
| `persistence` | JPA entities and Spring Data repositories | `domain` |
| `outbox` | outbox table, writer, relay, `EventPublisher` port and its adapter | nothing internal |
| `observability` | correlation id filter, JSON log formatter, business metrics | `domain` |
| `application` | use cases (`*Service`) and read models (`Views`) | all of the above |
| `api` | controllers, request records, problem details | `application`, `domain` |

The JPA entities are the persistence model and also carry the small amount of behaviour that touches state (`LoanContract.moveTo`), which delegates the decision to `ContractStatus`. Use cases return read-model records, never entities.

`ArchitectureTest` (ArchUnit) enforces the table on every build.

## Consequences

- Domain tests run without Spring. `PriceAmortizationTest` and `ContractStatusTest` cover the money and the state machine on their own.
- Entities are JPA classes, so the domain is not fully persistence-ignorant. I accept that: the rules that need protection live in pure classes, and the entities only call them.
- Entities reference each other by id (`customerId`, `agreementId`), not with `@ManyToOne`. Loading a contract never loads a customer graph by accident, and there is no lazy-loading surprise in the API. The cost is an extra `findById` where a join would have done.
