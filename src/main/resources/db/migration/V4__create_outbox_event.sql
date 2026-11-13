-- Transactional outbox: rows are written in the same transaction as the business change
-- and relayed to a message channel afterwards (at-least-once).
create table outbox_event (
    id             uuid         primary key,
    -- Insert order. The relay publishes in this order; created_at can tie.
    position       bigint       generated always as identity,
    aggregate_type varchar(60)  not null,
    aggregate_id   uuid         not null,
    event_type     varchar(80)  not null,
    payload        jsonb        not null,
    correlation_id varchar(64),
    created_at     timestamptz  not null,
    published_at   timestamptz,
    attempts       integer      not null default 0,
    last_error     varchar(500),
    constraint uk_outbox_position unique (position)
);

-- The relay only ever reads unpublished rows in insert order. A partial index stays small
-- because published rows drop out of it.
create index ix_outbox_unpublished on outbox_event (position) where published_at is null;
