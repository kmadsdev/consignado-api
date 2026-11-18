package io.github.kmadsdev.consignado.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two relay instances on one database, as in the cluster (2 to 3 pods, each with its relay on).
 * Each relay has its own transactions and connections; only PostgreSQL coordinates them.
 *
 * <p>Events of a few contracts are written one per transaction while both relays run, so a
 * relay can lock the first event of a contract while the next one commits and is visible to
 * the other relay. A small batch size cuts batches in the middle of a contract. The channel
 * is slow by a random fraction of a millisecond and fails each event once with a small
 * chance, so the relays overlap and the retry path runs too.
 */
class OutboxConcurrentRelayIT extends ApiTestBase {

    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_SIZE = 3;
    private static final int ITERATIONS = 30;
    private static final int CONTRACTS = 4;
    private static final int EVENTS_PER_ITERATION = 40;
    private static final Duration ITERATION_TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void twoRelaysPublishTheEventsOfEachContractInPositionOrderAndEachEventOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        SlowFlakyChannel channel = new SlowFlakyChannel();
        List<OutboxRelay> relays = List.of(relay(channel), relay(channel));
        ExecutorService pool = Executors.newFixedThreadPool(1 + relays.size());
        try {
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                List<UUID> contracts = new ArrayList<>();
                for (int i = 0; i < CONTRACTS; i++) {
                    contracts.add(UUID.randomUUID());
                }
                Set<UUID> written = ConcurrentHashMap.newKeySet();
                AtomicBoolean writerDone = new AtomicBoolean();

                List<Future<?>> running = new ArrayList<>();
                running.add(pool.submit(() -> {
                    for (int i = 0; i < EVENTS_PER_ITERATION; i++) {
                        UUID contract = contracts.get(ThreadLocalRandom.current().nextInt(CONTRACTS));
                        OutboxEvent event = new OutboxEvent("contract", contract, "contract.step" + i, "{}",
                                null, clock.instant());
                        tx.executeWithoutResult(status -> outbox.save(event));
                        written.add(event.getId());
                    }
                    writerDone.set(true);
                }));
                long deadline = System.nanoTime() + ITERATION_TIMEOUT.toNanos();
                for (OutboxRelay relay : relays) {
                    running.add(pool.submit(() -> {
                        while (!(writerDone.get() && channel.publishedIds().containsAll(written))
                                && System.nanoTime() < deadline) {
                            tx.execute(status -> relay.relayBatch());
                        }
                    }));
                }
                for (Future<?> future : running) {
                    future.get(ITERATION_TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS);
                }

                assertThat(channel.publishedIds()).as("iteration %d: every event published", iteration)
                        .containsAll(written);
                for (UUID contract : contracts) {
                    List<UUID> positionOrder = outbox.findByAggregateIdInOrder(contract).stream()
                            .map(OutboxEvent::getId).toList();
                    assertThat(channel.publishedFor(contract))
                            .as("iteration %d, contract %s: publish order equals position order", iteration, contract)
                            .containsExactlyElementsOf(positionOrder);
                }
            }
            assertThat(channel.duplicates()).as("events published twice by the relays").isEmpty();
            assertThat(channel.failures()).as("the retry path ran").isPositive();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRelayDoesNotClaimAnEventWhileAnotherRelayHoldsAnEarlierEventOfTheSameContract() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OutboxRelay drainer = relay(message -> { });
        while (!tx.execute(status -> drainer.relayBatch()).isEmpty()) {
            // other test classes leave events in the shared database
        }
        UUID contract = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID first = write(tx, contract);
        UUID second = write(tx, contract);
        UUID unrelated = write(tx, other);
        ExecutorService secondRelay = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(firstRelay -> {
                assertThat(outbox.lockNextBatch(MAX_ATTEMPTS, 1)).extracting(OutboxEvent::getId).containsExactly(first);

                List<UUID> claimed = join(secondRelay.submit(() -> tx.execute(status ->
                        outbox.lockNextBatch(MAX_ATTEMPTS, 10).stream().map(OutboxEvent::getId).toList())));

                // "second" waits until "first" is published; the other contract goes on.
                assertThat(claimed).containsExactly(unrelated);
            });
        } finally {
            secondRelay.shutdownNow();
        }
        List<UUID> afterwards = tx.execute(status ->
                outbox.lockNextBatch(MAX_ATTEMPTS, 10).stream().map(OutboxEvent::getId).toList());
        assertThat(afterwards).containsExactly(first, second, unrelated);
    }

    private UUID write(TransactionTemplate tx, UUID contract) {
        OutboxEvent event = new OutboxEvent("contract", contract, "contract.step", "{}", null, clock.instant());
        tx.executeWithoutResult(status -> outbox.save(event));
        return event.getId();
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OutboxRelay relay(EventPublisher channel) {
        return new OutboxRelay(outbox, channel, clock, new SimpleMeterRegistry(), BATCH_SIZE, MAX_ATTEMPTS);
    }

    /**
     * Records accepted events in the order the relays handed them over. It ignores events of
     * other test classes that still wait in the shared database.
     */
    private static final class SlowFlakyChannel implements EventPublisher {

        private final List<OutboxMessage> published = new CopyOnWriteArrayList<>();
        private final Set<UUID> publishedIds = ConcurrentHashMap.newKeySet();
        private final Set<UUID> failedOnce = ConcurrentHashMap.newKeySet();
        private final Map<UUID, Integer> duplicates = new ConcurrentHashMap<>();

        @Override
        public void publish(OutboxMessage message) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            LockSupport.parkNanos(random.nextLong(200_000, 800_000));
            if (random.nextInt(10) == 0 && failedOnce.add(message.id())) {
                throw new IllegalStateException("channel unavailable (simulated)");
            }
            synchronized (this) {
                if (!publishedIds.add(message.id())) {
                    duplicates.merge(message.id(), 1, Integer::sum);
                }
                published.add(message);
            }
        }

        Collection<UUID> publishedIds() {
            return publishedIds;
        }

        List<UUID> publishedFor(UUID contract) {
            return published.stream().filter(m -> m.aggregateId().equals(contract)).map(OutboxMessage::id).toList();
        }

        Map<UUID, Integer> duplicates() {
            return duplicates;
        }

        int failures() {
            return failedOnce.size();
        }
    }
}
