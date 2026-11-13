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
     */
    @Query(value = """
            select * from outbox_event
            where published_at is null and attempts < :maxAttempts
            order by position
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Query(value = "select * from outbox_event where aggregate_id = :aggregateId order by position", nativeQuery = true)
    List<OutboxEvent> findByAggregateIdInOrder(@Param("aggregateId") UUID aggregateId);

    @Query("select count(e) from OutboxEvent e where e.publishedAt is null")
    long countPending();
}
