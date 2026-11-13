package io.github.kmadsdev.consignado.testsupport;

import io.github.kmadsdev.consignado.outbox.EventPublisher;
import io.github.kmadsdev.consignado.outbox.OutboxMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test adapter: records what was published and can fail on demand for one aggregate. */
public class RecordingEventPublisher implements EventPublisher {

    private final List<OutboxMessage> published = new CopyOnWriteArrayList<>();
    private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

    @Override
    public void publish(OutboxMessage message) {
        AtomicInteger left = failuresLeft.get(message.aggregateId());
        if (left != null && left.getAndDecrement() > 0) {
            throw new IllegalStateException("channel unavailable (simulated)");
        }
        published.add(message);
    }

    public void failNextFor(UUID aggregateId, int times) {
        failuresLeft.put(aggregateId, new AtomicInteger(times));
    }

    public List<OutboxMessage> publishedFor(UUID aggregateId) {
        return published.stream().filter(m -> m.aggregateId().equals(aggregateId)).toList();
    }
}
