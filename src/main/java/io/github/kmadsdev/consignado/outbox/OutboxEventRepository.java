package io.github.kmadsdev.consignado.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks the next unpublished rows in insert order. SKIP LOCKED lets several relay
     * instances run without blocking each other: each takes rows the others did not lock.
     * Uses ix_outbox_unpublished.
     *
     * <p>A row that reached the attempt limit is dead, and it also holds back every later
     * row of the same aggregate: publishing them would hand consumers "approved" for a
     * contract they never saw "proposed". A person has to repair the dead row first.
     */
    @Query(value = """
            select * from outbox_event e
            where e.published_at is null and e.attempts < :maxAttempts
              and not exists (
                  select 1 from outbox_event dead
                  where dead.aggregate_id = e.aggregate_id
                    and dead.published_at is null
                    and dead.attempts >= :maxAttempts
                    and dead.position < e.position)
            order by e.position
            limit :batchSize
            for update of e skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Query(value = "select * from outbox_event where aggregate_id = :aggregateId order by position", nativeQuery = true)
    List<OutboxEvent> findByAggregateIdInOrder(@Param("aggregateId") UUID aggregateId);

    @Query("select count(e) from OutboxEvent e where e.publishedAt is null")
    long countPending();
}
