# ADR 0013: Per-request timing headers

- Status: accepted
- Date: 2026-11-17

## Context

The platform draws every request as a flow: browser, Cloudflare edge, gateway, Entra ID, Key Vault, the API host, and back. The gateway measures the time up to the API host and the time of the whole call, but it cannot see inside the Spring Boot API. So no drawing showed the database, and nothing proved that the guest loan preview (`POST /api/v1/simulations/preview`) really touches no table.

The API must say, on every response, what it did: how long it worked and what database work it did. The gateway reads that and adds the `app` and `database` stages to the trace (contract addendum 2 of the platform).

## Decision

**Two response headers on every request outside `/actuator`.**

```
Server-Timing: app;dur=6.6, db;dur=2.4;desc="3 statements"
X-Consignado-Timing: {"instance":"consignado-api-5d897fb475-ffmb4","appMs":6.6,
  "db":{"ms":2.4,"statements":3,"select":2,"insert":1,"update":0,"delete":0,"other":0,"connectionMs":0.0,"transactions":1}}
```

(The JSON is one line; it is wrapped here to fit.) When no statement ran, `Server-Timing` has no `db` entry and the JSON has `"db":null`.

- `Server-Timing` is the W3C header. Browser developer tools show it without any code.
- `X-Consignado-Timing` is for the gateway: one line of JSON with the counts by kind.
- `instance`: `HOSTNAME` (the pod name on AKS), else the first 12 characters of `WEBSITE_INSTANCE_ID` (Azure Functions), else `local`. Read once at start. A value that is not a short plain token is replaced with `local`, because it goes into a header and a JSON string.
- `appMs`: from `RequestTimingFilter` to the moment the response is about to commit. It includes the database time.
- `db.ms`: the sum of the execution times of the JDBC statements. `connectionMs`: the time in `DataSource.getConnection()` (the wait for a pooled connection). `transactions`: the commits and rollbacks of a whole transaction. All times in milliseconds with one decimal.

**How it is measured.**

- `RequestTimingFilter` (order: right after `CorrelationIdFilter`) opens a `RequestTiming` recorder and binds it to the request thread, the same way the MDC holds the correlation id.
- The Hikari `DataSource` is wrapped in a proxy of the small library `net.ttddyy:datasource-proxy`, by a `BeanPostProcessor`. The bean stays the same pool: Flyway, JPA, the health check and the Hikari metrics go through the proxy, which forwards `unwrap`. A listener classifies each executed statement by its first keyword (after blanks and comments), times it, and counts `getConnection`, `commit` and `rollback`. A `SELECT ... FOR UPDATE` (the customer row lock) is a select; its wait for the lock is part of its time.
- Work that is not a request finds no recorder on its thread and is not counted: the scheduled outbox relay, Flyway at start, the Prometheus gauge. It cannot fail either: the listener returns at once.
- Spring writes a JSON body and flushes it inside the handler, so headers set after `chain.doFilter` would be too late. The filter wraps the response (`CommitAwareResponse`) and writes the headers just before the first body byte, a flush, `sendError` or `sendRedirect`; for a response with no body (204, a `HEAD` request) it writes them at the end. Problem responses (404, 409, 422, 500) go through the same path. A `reset()` before the commit writes them again. The body is not buffered.
- The request log line of `CorrelationIdFilter` gets the same numbers (`appMs=6.6 dbMs=2.4 statements=3 select=2 insert=1 ...`), and Micrometer gets the distribution summary `consignado.db.request.statements` (every request) and the timer `consignado.db.request.time` (requests that ran a statement). Neither has tags.

**Why datasource-proxy and not Hibernate alone.** Hibernate has a `StatementInspector` (sees the SQL, no time) and session events (time per statement, connection acquisition). Together they would work for JPA, but they see only what goes through a Hibernate session: not Spring JDBC, not the outbox `SKIP LOCKED` query if it moves to `JdbcTemplate`, not a native driver call. The proxy sits at the JDBC boundary, so it counts every statement of the request whatever made it. The library is small, has no runtime dependencies, and is used only through two listener interfaces.

## What is exposed, and what is not on purpose

- Exposed: numbers only. Times, counts by kind, the instance name.
- Never exposed: SQL text, table names, parameter values, row counts, entity data, the connection URL. The recorder has no field that could hold them. The SQL string is read only to find its first keyword. The tests check that the headers and the log line contain no `insert into`, no CPF and no customer name.
- The instance name is visible to every caller. A pod name says little (it changes on each deploy) and it helps a reviewer see which pod answered.

## Cost per request

Measured in a throwaway probe (JDK 21 in a container, not committed): the filter adds about 1.4 µs per request (header formatting and the two meters), and the proxy adds about 1 to 1.5 µs per `getConnection` plus statement on a mocked driver. A database round trip here costs milliseconds, so this is far below 0.1% of a request. Each response gets about 250 bytes of headers.

## Consequences

- The gateway can draw the `app` and `database` stages from real numbers, and the stateless preview proves on every call that it runs no statement (`"db":null`). The integration tests check it.
- `appMs` ends at the commit of the response, not when the last byte leaves the socket; a slow client does not inflate it.
- Statements that run after the response committed (none today; `open-in-view` is off) are counted in the log line and metrics but not in the headers.
- A request that only opens a connection and runs no statement reports `"db":null`; its connection wait is not shown.
- A request that hands work to another thread (none today) would not see that work. The recorder is thread-bound on purpose, so that background work is never mixed in.

## What a bank would use instead

Distributed tracing: OpenTelemetry instrumentation in every service, W3C Trace Context (`traceparent`) passed from the edge to the database client, spans exported to a collector (Azure Monitor, Jaeger, Tempo). Each JDBC call becomes a span with its own timing, and SQL text is kept in the tracing back end under its access rules, never sent to the client.

This header approach imitates that for one hop: one service reports a summary of its own spans to its direct caller, in the response, without a collector. It costs nothing to run, which matters on a US$60 budget, but it has no sampling, no span tree and no cross-service correlation beyond the correlation id. Moving to OpenTelemetry later does not need these headers removed: `Server-Timing` can stay as the browser-facing summary.
