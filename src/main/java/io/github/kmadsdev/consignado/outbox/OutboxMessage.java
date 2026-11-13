package io.github.kmadsdev.consignado.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * What a publisher receives. {@code id} is stable across retries, so a consumer can
 * de-duplicate (delivery is at-least-once). {@code aggregateId} is the natural
 * partition/routing key: events of one contract must stay in order.
 */
public record OutboxMessage(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        String correlationId,
        Instant occurredAt) {
}
