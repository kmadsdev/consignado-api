package io.github.kmadsdev.consignado.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next rows to publish, in insert order. Safe with any number of relay
     * instances (ADR 0005). Uses ix_outbox_unpublished.
     *
     * <p>Step 1 ({@code locked}) locks the next unpublished rows with SKIP LOCKED, so relay
     * instances do not block each other. Step 2 returns only the rows that have no earlier
     * unpublished row of the same aggregate outside this batch. Such an earlier row is
     * locked by another relay (it may be publishing it now), or it was written after the
     * batch limit, or it is dead. The later row stays unpublished, and a later poll takes it
     * after the earlier one is published. So every aggregate is published in position
     * order, whatever the number of relays. The rows dropped in step 2 stay locked until
     * this transaction ends; the other relays skip them, which is correct, as they wait anyway.
     *
     * <p>A row that reached the attempt limit is dead, and it also holds back every later
     * row of the same aggregate: publishing them would hand consumers "approved" for a
     * contract they never saw "proposed". A person has to repair the dead row first. Step 1
     * leaves these rows out before the limit, so they cannot fill every batch.
     */
    @Query(value = """
            with locked as materialized (
                select e.* from outbox_event e
                where e.published_at is null and e.attempts < :maxAttempts
                  and not exists (
                      select 1 from outbox_event dead
                      where dead.aggregate_id = e.aggregate_id
                        and dead.published_at is null
                        and dead.attempts >= :maxAttempts
                        and dead.position < e.position)
                order by e.position
                limit :batchSize
                for update of e skip locked)
            select l.* from locked l
            where not exists (
                select 1 from outbox_event earlier
                where earlier.aggregate_id = l.aggregate_id
                  and earlier.published_at is null
                  and earlier.position < l.position
                  and earlier.id not in (select id from locked))
            order by l.position
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Query(value = "select * from outbox_event where aggregate_id = :aggregateId order by position", nativeQuery = true)
    List<OutboxEvent> findByAggregateIdInOrder(@Param("aggregateId") UUID aggregateId);

    @Query("select count(e) from OutboxEvent e where e.publishedAt is null")
    long countPending();
}
