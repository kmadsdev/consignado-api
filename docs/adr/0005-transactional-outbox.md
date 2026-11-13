# ADR 0005: Transactional outbox behind a publisher port

- Status: accepted
- Date: 2026-11-13

## Context

Other systems (payroll deduction, notification, accounting) need to know when a contract changes status. If the service writes to the database and then sends a message, a crash between the two loses the message, or sends a message for a change that rolled back. I do not want to pick a broker in this project, because that is a platform decision.

## Decision

- **Outbox table.** `outbox_event` gets one row per status change, written by `OutboxWriter` with `Propagation.MANDATORY`, so it always joins the transaction that changes the contract. Both commit or neither does. The payload has ids, statuses and amounts, never personal data.
- **Relay.** `OutboxRelay.relayBatch()` runs every 2 seconds (`OutboxScheduler`). It selects unpublished rows in insert order with `FOR UPDATE SKIP LOCKED`, calls the port, and marks each row published, or increments `attempts` and stores `last_error`.
- **Port.** `EventPublisher.publish(OutboxMessage)`. The only adapter today is `LoggingEventPublisher`, which writes the event to the log.
- **Ordering.** Rows have an identity column `position`. Within one batch, if an event fails, later events of the same contract are skipped, so a consumer never gets `contract.approved` before `contract.proposed`.
- **Delivery** is at-least-once. `OutboxMessage.id` is stable across retries so consumers can de-duplicate.

## How a broker plugs in

Write one class that implements `EventPublisher`, for example a Kafka, RabbitMQ or SQS adapter, and enable it with `consignado.outbox.publisher=<name>`. The adapter must:

1. Return only after the broker acknowledged the message (synchronous send or wait on the future), and throw otherwise, so the relay knows the outcome.
2. Use `aggregateId` (the contract id) as the partition or routing key, so events of one contract stay in order.
3. Put `id` and `correlationId` in message headers.

Nothing else changes: the use cases only know `OutboxWriter`.

## Consequences and limits

- The relay holds row locks while it publishes. Batches must stay small (`batch-size`, default 100) and the broker call must have a timeout.
- `SKIP LOCKED` lets several instances relay at once, but then two instances can take two events of the same contract and publish them out of order. With more than one instance, I would partition the relay by `hash(aggregate_id)` or run it on a single leader.
- Identity values are assigned at insert time, not at commit time, so across *different* contracts a later position can commit first. Per contract this cannot happen, because changes to one contract are serialized by its version.
- After `max-attempts` (default 10) a row stops being retried, and the later events of the same contract wait behind it (the relay query skips them), so a consumer never gets `contract.approved` for a contract it never saw proposed. A person has to repair the row and reset `attempts`. The `consignado_outbox_pending` gauge shows it; nothing pages anyone yet.
- Published rows are never deleted. A real system needs a cleanup job.
- `OutboxIT` covers: events in order, a failed publish that holds back the same contract and then recovers, a dead event that keeps holding it back, and a rejected approval that writes no event.
