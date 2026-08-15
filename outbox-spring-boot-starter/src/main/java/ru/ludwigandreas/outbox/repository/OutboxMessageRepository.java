package ru.ludwigandreas.outbox.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Requires {@code @EnableJpaRepositories(repositoryBaseClass = ru.ludwigandreas.db.core.repository.BaseRepositoryImpl.class)}
 * on the consuming application (same requirement {@code db-core} already imposes on every
 * {@link BaseRepository} subinterface), scanning {@code ru.ludwigandreas.outbox.repository} alongside
 * the application's own repository packages.
 */
public interface OutboxMessageRepository extends BaseRepository<OutboxMessage, UUID> {

    /**
     * Atomically claims up to {@code batchSize} due rows ({@code PENDING}/{@code FAILED},
     * {@code next_attempt_at <= now}) by flipping them to {@code PROCESSING}, using
     * {@code FOR UPDATE SKIP LOCKED} so concurrent poller instances claim disjoint batches without
     * blocking each other.
     * <p>
     * Rows with a non-null {@code ordering_key} are additionally skipped while an earlier
     * (lower {@code created_at}/{@code id}) unresolved row shares the same key, giving best-effort
     * head-of-line-blocked ordering per key without a global lock.
     * <p>
     * Deliberately a single native {@code UPDATE ... RETURNING *} rather than a QueryDSL/JPQL
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} followed by a second update: one round trip, and exact
     * control over the generated SQL for a query this hot. Must NOT be annotated {@code @Modifying} -
     * that would force {@code executeUpdate()} semantics, which discards the {@code RETURNING} result
     * set; omitting it lets Spring Data/Hibernate execute it as a result-set-returning query and
     * hydrate {@link OutboxMessage} entities from the returned rows.
     */
    @Query(value = """
            UPDATE outbox_message
            SET status = 'PROCESSING', locked_at = now(), locked_by = :lockOwner
            WHERE id IN (
                SELECT id FROM outbox_message m
                WHERE m.status IN ('PENDING', 'FAILED')
                  AND m.next_attempt_at <= :now
                  AND (
                    m.ordering_key IS NULL
                    OR NOT EXISTS (
                        SELECT 1 FROM outbox_message m2
                        WHERE m2.ordering_key = m.ordering_key
                          AND m2.id <> m.id
                          AND m2.status IN ('PENDING', 'FAILED', 'PROCESSING')
                          AND (m2.created_at, m2.id) < (m.created_at, m.id)
                    )
                  )
                ORDER BY m.created_at, m.id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<OutboxMessage> pollBatch(@Param("batchSize") int batchSize, @Param("now") Instant now, @Param("lockOwner") String lockOwner);

    /**
     * Crash recovery: a poller instance that claimed rows (flipped them to {@code PROCESSING}) and
     * died before recording an outcome would otherwise strand them forever. Resets any row still
     * {@code PROCESSING} with {@code locked_at} older than {@code staleBefore} back to {@code PENDING}.
     */
    @Modifying
    @Query("update OutboxMessage m set m.status = ru.ludwigandreas.outbox.entity.OutboxStatus.PENDING, "
            + "m.lockedAt = null, m.lockedBy = null "
            + "where m.status = ru.ludwigandreas.outbox.entity.OutboxStatus.PROCESSING and m.lockedAt < :staleBefore")
    int reclaimStale(@Param("staleBefore") Instant staleBefore);

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);
}
