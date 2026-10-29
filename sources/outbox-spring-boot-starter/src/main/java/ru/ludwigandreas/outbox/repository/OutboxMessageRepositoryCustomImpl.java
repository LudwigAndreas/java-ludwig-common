package ru.ludwigandreas.outbox.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.job.core.claim.SkipLockedClaim;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Claims due outbox rows with {@code job-core}'s shared {@code FOR UPDATE SKIP LOCKED} statement.
 *
 * <p>Found by Spring Data through the {@code ...Impl} naming convention on the fragment interface;
 * it needs no annotation and no registration.
 *
 * <h2>The outbox-specific part: per-key ordering</h2>
 *
 * <p>Only the due predicate differs from every other claim in the platform. A row carrying an
 * {@code ordering_key} is skipped while any earlier unresolved row shares that key, which gives
 * best-effort head-of-line ordering per key without a global lock and without serializing messages
 * that have nothing to do with each other. Rows with no ordering key are unaffected.
 */
public class OutboxMessageRepositoryCustomImpl implements OutboxMessageRepositoryCustom {

    private static final String TABLE = "outbox_message";

    private static final String CLAIM_SET_CLAUSE =
            "status = 'PROCESSING', locked_at = now(), locked_by = :lockOwner";

    private static final String DUE_PREDICATE = """
            t.status IN ('PENDING', 'FAILED')
              AND t.next_attempt_at <= :now
              AND (
                t.ordering_key IS NULL
                OR NOT EXISTS (
                    SELECT 1 FROM outbox_message m2
                    WHERE m2.ordering_key = t.ordering_key
                      AND m2.id <> t.id
                      AND m2.status IN ('PENDING', 'FAILED', 'PROCESSING')
                      AND (m2.created_at, m2.id) < (t.created_at, t.id)
                )
              )
            """;

    /** Oldest first, tie-broken by id so the claim order is total and a backlog cannot starve its own head. */
    private static final String ORDER_BY = "t.created_at, t.id";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * <p>Read-write on purpose, and not merely because it writes: a claim committed in its own short
     * transaction is what makes the rows visible as claimed to every other instance before this one
     * starts making network calls with them.
     */
    @Override
    @Transactional
    public List<OutboxMessage> pollBatch(int batchSize, Instant now, String lockOwner) {
        return SkipLockedClaim.claim(entityManager, OutboxMessage.class, TABLE,
                CLAIM_SET_CLAUSE, DUE_PREDICATE, ORDER_BY, batchSize,
                Map.of("lockOwner", lockOwner, "now", now));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Native, because neither JPQL nor QueryDSL can express it: a bounded delete needs {@code LIMIT} in the
     * selecting subquery and {@code FOR UPDATE SKIP LOCKED} on it, and deleting the history rows in the same
     * statement needs a data-modifying CTE. Both deletes see the same locked set of ids, so a message is never
     * removed without its history or the other way round. Its own transaction, because the purge runs from a
     * scheduler thread and each batch must commit on its own.
     */
    @Override
    @Transactional
    public int purgeFinishedBefore(OutboxStatus status, Instant cutoff, int limit) {
        return entityManager.createNativeQuery("""
                        WITH doomed AS (
                            SELECT id FROM outbox_message
                            WHERE status = :status AND finished_at < :cutoff
                            ORDER BY finished_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        ), history AS (
                            DELETE FROM outbox_status_history WHERE outbox_message_id IN (SELECT id FROM doomed)
                        )
                        DELETE FROM outbox_message WHERE id IN (SELECT id FROM doomed)
                        """)
                .setParameter("status", status.name())
                .setParameter("cutoff", cutoff)
                .setParameter("limit", limit)
                .executeUpdate();
    }
}
