# ADR 0005: Transactional outbox behind a publisher port

- Status: accepted
- Date: 2026-11-13
- Updated: 2026-11-18 (order per contract with several relays)

## Context

Other systems (payroll deduction, notification, accounting) need to know when a contract changes status. If the service writes to the database and then sends a message, a crash between the two loses the message, or sends a message for a change that rolled back. I do not want to pick a broker in this project, because that is a platform decision.

## Decision

- **Outbox table.** `outbox_event` gets one row per status change, written by `OutboxWriter` with `Propagation.MANDATORY`, so it always joins the transaction that changes the contract. Both commit or neither does. The payload has ids, statuses and amounts, never personal data.
- **Relay.** `OutboxRelay.relayBatch()` runs every 2 seconds (`OutboxScheduler`) in every pod. It claims unpublished rows in insert order (see "Several relays" below), calls the port, and marks each row published, or increments `attempts` and stores `last_error`.
- **Port.** `EventPublisher.publish(OutboxMessage)`. The only adapter today is `LoggingEventPublisher`, which writes the event to the log.
- **Ordering.** Rows have an identity column `position`. Events of one contract are published in `position` order, with any number of relays. Within one batch, if an event fails, later events of the same contract are skipped, so a consumer never gets `contract.approved` before `contract.proposed`.
- **Delivery** is at-least-once. `OutboxMessage.id` is stable across retries so consumers can de-duplicate.

## Several relays

The cluster runs 2 to 3 pods (`deploy/k8s/40-deployment.yaml`, `60-hpa.yaml`), and the relay is on in each pod. The Functions host turns its relay off. Plain `FOR UPDATE SKIP LOCKED` is not enough: relay A locks `proposed` of a contract, the next event `approved` commits, relay B skips the locked row, takes `approved` and can publish it before A publishes `proposed`.

**Decision: the claim query itself keeps the order.** `OutboxEventRepository.lockNextBatch`:

```sql
with locked as materialized (
    select e.* from outbox_event e
    where e.published_at is null and e.attempts < :maxAttempts
      and not exists (                      -- rows behind a dead row
          select 1 from outbox_event dead
          where dead.aggregate_id = e.aggregate_id
            and dead.published_at is null
            and dead.attempts >= :maxAttempts
            and dead.position < e.position)
    order by e.position
    limit :batchSize
    for update of e skip locked)
select l.* from locked l
where not exists (                          -- an earlier row I do not hold
    select 1 from outbox_event earlier
    where earlier.aggregate_id = l.aggregate_id
      and earlier.published_at is null
      and earlier.position < l.position
      and earlier.id not in (select id from locked))
order by l.position
```

A relay publishes a row only when every earlier unpublished row of the same contract is in its own batch, in front of it. An earlier row outside the batch is locked by another relay, or past the batch limit, or dead; in each case the later row waits for a later poll. Why this is safe:

- The second step reads one snapshot. If it sees the earlier row as published, that relay called the channel and committed before. If the commit came later, it still sees the row unpublished and drops the later row: too careful, never too early.
- The later row cannot be visible while the earlier one is not. Changes of one contract are serialized by its version (ADR 0003), so the earlier row commits first.
- The relay that holds the lowest unpublished row of a contract can always publish it, so the system always moves forward.
- The dead-row filter stays in the first step, before the limit, so rows behind a dead row cannot fill every batch.

**Guaranteed:** order per contract (per `aggregate_id`) with any number of relays; each relay hands an event to the channel once per successful claim, and a published row is never claimed again. **Not guaranteed:** a global order across contracts (two relays publish different contracts at the same time, and positions of different contracts can commit out of order anyway); exactly-once delivery. Delivery stays at-least-once: if the channel accepts an event and the pod dies before the commit, the row is still unpublished and is sent again, in order, after it. Consumers de-duplicate on `id`.

**Cost.** One extra anti-join per poll on the small set of unpublished rows (partial index `ix_outbox_unpublished`). Rows dropped in the second step stay locked until the relay's transaction ends, so they can wait for one more poll after the earlier row is published. While one relay is publishing a contract, the other relays cannot help with that contract, but they publish the other contracts in parallel.

**Alternatives I did not take.**

- *One active relay through `pg_try_advisory_lock` held for the poll.* Fewer lines, but it serialises all publishing: one pod works, the others idle, and the throughput is that of one relay whatever the replica count. A pod that dies holding the session lock also blocks the others until PostgreSQL notices the dead connection.
- *Partition by `hash(aggregate_id)` per pod.* Needs a stable pod index and a rebalance when the HPA changes the replica count; a missing pod stops its partition.
- *`pg_try_advisory_xact_lock(hashtext(aggregate_id))` per contract.* Also correct, but hash collisions block unrelated contracts, and it adds a second lock system next to the row locks.

`OutboxConcurrentRelayIT` proves it: two relay instances (own transactions and connections, batch size 3) on one PostgreSQL, 30 rounds of 40 events of 4 contracts, written one per transaction while both relays run, a slow channel that fails some events once; for every contract the publish order equals the position order, and no event is published twice. A second test holds the first event of a contract in one relay and checks that the other relay claims only the event of another contract. Both tests fail on the old query.

## How a broker plugs in

Write one class that implements `EventPublisher`, for example a Kafka, RabbitMQ or SQS adapter, and enable it with `consignado.outbox.publisher=<name>`. The adapter must:

1. Return only after the broker acknowledged the message (synchronous send or wait on the future), and throw otherwise, so the relay knows the outcome.
2. Use `aggregateId` (the contract id) as the partition or routing key, so events of one contract stay in order.
3. Put `id` and `correlationId` in message headers.

Nothing else changes: the use cases only know `OutboxWriter`.

## Consequences and limits

- The relay holds row locks while it publishes. Batches must stay small (`batch-size`, default 100) and the broker call must have a timeout.
- Several relays run at once (one per pod) and keep the order of each contract (see "Several relays").
- Identity values are assigned at insert time, not at commit time, so across *different* contracts a later position can commit first. Per contract this cannot happen, because changes to one contract are serialized by its version.
- After `max-attempts` (default 10) a row stops being retried, and the later events of the same contract wait behind it (the relay query skips them), so a consumer never gets `contract.approved` for a contract it never saw proposed. A person has to repair the row and reset `attempts`. The `consignado_outbox_pending` gauge shows it; nothing pages anyone yet.
- Published rows are never deleted. A real system needs a cleanup job.
- `OutboxIT` covers: events in order, a failed publish that holds back the same contract and then recovers, a dead event that keeps holding it back, and a rejected approval that writes no event.
- `OutboxConcurrentRelayIT` covers two relays on one database (see "Several relays").
