package io.github.kmadsdev.consignado.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves one batch from the outbox to the {@link EventPublisher}.
 *
 * <p>Ordering: rows are read in insert order. When an event fails, later events of the
 * same aggregate are skipped in this batch, so a consumer never sees "approved" before
 * "proposed". Events of other aggregates continue.
 *
 * <p>Failures: the row keeps {@code attempts} and {@code last_error}. After
 * {@code maxAttempts} the row is no longer picked up and needs a person to look at it
 * (the pending count metric shows it). Later events of the same aggregate wait behind it.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxEventRepository repository, EventPublisher publisher, Clock clock, MeterRegistry meters,
                       @Value("${consignado.outbox.batch-size:100}") int batchSize,
                       @Value("${consignado.outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.published = Counter.builder("consignado.outbox.published")
                .description("Outbox events accepted by the publisher").register(meters);
        this.failed = Counter.builder("consignado.outbox.failed")
                .description("Outbox publish attempts that failed").register(meters);
        meters.gauge("consignado.outbox.pending", repository, OutboxEventRepository::countPending);
    }

    public record RelayResult(int published, int failed, int skipped) {
        public boolean isEmpty() {
            return published + failed + skipped == 0;
        }
    }

    /** One transaction per batch: row locks are held while publishing, so keep batches small. */
    @Transactional
    public RelayResult relayBatch() {
        List<OutboxEvent> batch = repository.lockNextBatch(maxAttempts, batchSize);
        Set<UUID> blockedAggregates = new HashSet<>();
        int ok = 0;
        int errors = 0;
        int skipped = 0;

        for (OutboxEvent event : batch) {
            if (blockedAggregates.contains(event.getAggregateId())) {
                skipped++;
                continue;
            }
            try (var ignored = MDC.putCloseable("correlationId", event.getCorrelationId())) {
                publisher.publish(event.toMessage());
                event.markPublished(clock.instant());
                published.increment();
                ok++;
            } catch (RuntimeException e) {
                event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                blockedAggregates.add(event.getAggregateId());
                failed.increment();
                errors++;
                log.warn("outbox publish failed id={} type={} attempt={}",
                        event.getId(), event.getEventType(), event.getAttempts(), e);
            }
        }
        return new RelayResult(ok, errors, skipped);
    }
}
