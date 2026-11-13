# ADR 0006: Observability with open formats only

- Status: accepted
- Date: 2026-11-13

## Context

I want logs, metrics and health checks that any platform can consume, without tying the code to one vendor's agent or schema.

## Decision

- **Correlation id.** `CorrelationIdFilter` reads `X-Correlation-Id`. If the value is a short plain token it is kept, otherwise it is replaced with a new UUID (header values end up in logs, so free text is not accepted). The id goes into the MDC, the response header, every problem response and every outbox event, and the relay puts it back into the MDC when it publishes.
- **Structured logs.** One JSON object per line through `JsonLogFormatter`, a small `StructuredLogFormatter` on top of Spring Boot's built-in structured logging. Fields: `timestamp`, `level`, `logger`, `thread`, `message`, `correlationId`, `exception`. No extra logging dependency.
- **Health.** Actuator liveness and readiness groups. Readiness includes the database; liveness does not, so a database outage takes the pod out of the load balancer but does not restart it.
- **Metrics.** Micrometer with the Prometheus registry. Besides HTTP, JVM and connection pool metrics: `consignado_contract_transitions_total{from,to}`, `consignado_simulations_total{outcome}`, `consignado_outbox_published_total`, `consignado_outbox_failed_total` and the gauge `consignado_outbox_pending`.
- **Personal data.** CPF is never logged: `Cpf.toString()` returns a masked value, error messages do not include it, there is no CPF in any URL, and the request log line has no query string.

## Consequences

- Tracing (spans across services) is not set up. Micrometer Tracing with an OpenTelemetry exporter would be the next step; the correlation id covers the single-service case for now.
- The `consignado_outbox_pending` gauge runs a `count(*)` on each scrape. That is fine at this size; a large table would need an estimate or a cached value.
- Actuator shares the application port. In a real deployment I would move it to a separate management port that is not exposed publicly.
