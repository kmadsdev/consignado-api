package io.github.kmadsdev.consignado.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the outbox. {@code MANDATORY}: there must already be a transaction,
 * the one that changes the business data. Both commit together or neither does, so an
 * event is never sent for a change that rolled back, and never lost for one that committed.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        repository.save(new OutboxEvent(aggregateType, aggregateId, eventType, toJson(payload),
                MDC.get("correlationId"), clock.instant()));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event payload cannot be serialized: " + payload.getClass(), e);
        }
    }
}
