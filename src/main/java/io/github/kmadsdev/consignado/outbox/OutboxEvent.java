package io.github.kmadsdev.consignado.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** A row of outbox_event. The identity column "position" is read only by SQL, not mapped. */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent implements Persistable<UUID> {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    /**
     * The id is assigned in Java and there is no version column, so Spring Data cannot tell a
     * new row from a stored one and its save() would call merge(): one SELECT before every
     * INSERT. This flag answers the question instead.
     */
    @Transient
    private boolean isNew = true;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 60)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 80)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload,
                String correlationId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    OutboxMessage toMessage() {
        return new OutboxMessage(id, aggregateType, aggregateId, eventType, payload, correlationId, createdAt);
    }

    void markPublished(Instant now) {
        attempts++;
        publishedAt = now;
        lastError = null;
    }

    void markFailed(String error) {
        attempts++;
        lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}
