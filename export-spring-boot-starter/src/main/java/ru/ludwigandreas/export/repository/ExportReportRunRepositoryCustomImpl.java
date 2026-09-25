package ru.ludwigandreas.export.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.job.core.claim.SkipLockedClaim;

/**
 * Claims, reclaims and heartbeats report runs with {@code job-core}'s shared
 * {@code FOR UPDATE SKIP LOCKED} statement.
 *
 * <p>Found by Spring Data through the {@code ...Impl} naming convention on the fragment interface; it
 * needs no annotation and no registration.
 *
 * <h2>Why the claim goes through job-core rather than being written here</h2>
 *
 * <p>Because it is the same statement the outbox and the reconciliation module claim with, and the
 * subtle parts - the {@code IN (SELECT ... FOR UPDATE SKIP LOCKED)} shape that avoids locking rows
 * the update will not touch, the {@code RETURNING *} that makes claim-and-read one round trip - are
 * the kind of thing that is correct in one copy and subtly wrong in the third. This class supplies
 * only what is genuinely specific to reports: which rows are due, in what order, and what "claimed"
 * means for this table.
 *
 * <h2>The index this runs on</h2>
 *
 * <p>{@code idx_export_report_run_claim}, a partial index on {@code (next_attempt_at, id)} where
 * {@code status = 'PENDING'}. Its column order is exactly {@link #DUE_PREDICATE} followed by
 * {@link #ORDER_BY}, and the partial predicate is what keeps it from growing into an index over every
 * report ever produced. The reclaim statement runs on {@code idx_export_report_run_reclaim} instead.
 *
 * <h2>Why a report claims one row at a time</h2>
 *
 * <p>Unlike the outbox, which claims a batch, the poller here claims as many runs as it can actually
 * start. A report run holds a thread for minutes; a batch claim would mean an instance took work it
 * could only begin one piece of, and the rest would sit claimed and idle while another instance had
 * capacity. The batch size is configuration rather than a constant precisely so an estate with very
 * short reports can raise it.
 */
public class ExportReportRunRepositoryCustomImpl implements ExportReportRunRepositoryCustom {

    private static final String TABLE = "export_report_run";

    /**
     * What "claimed" means here: RUNNING, owned by this instance, leased, and one attempt further in.
     *
     * <p>The attempt is counted at claim time rather than at completion, which is deliberate. A run
     * that kills the instance executing it never reaches completion, so counting there would let it
     * be reclaimed forever; counting here means such a run exhausts its budget and fails with
     * something an operator can read.
     */
    private static final String CLAIM_SET_CLAUSE = """
            status = 'RUNNING',
            claimed_by = :owner,
            claimed_at = :now,
            heartbeat_at = :now,
            lease_until = :leaseUntil,
            attempts = export_report_run.attempts + 1,
            started_at = COALESCE(export_report_run.started_at, :now)
            """;

    private static final String DUE_PREDICATE = "t.status = 'PENDING' AND t.next_attempt_at <= :now";

    /** Oldest due first, then by id so that two runs sharing a timestamp still claim in a fixed order. */
    private static final String ORDER_BY = "t.next_attempt_at, t.id";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public List<ExportReportRun> claimDue(String owner, Instant now, Duration lease, int limit) {
        return SkipLockedClaim.claim(entityManager, ExportReportRun.class, TABLE, CLAIM_SET_CLAUSE,
                DUE_PREDICATE, ORDER_BY, limit,
                Map.of("owner", owner, "now", now, "leaseUntil", now.plus(lease)));
    }

    /**
     * Returns expired leases to PENDING.
     *
     * <p>Not a {@code SkipLockedClaim}: nothing is being claimed here, and the rows are by definition
     * not locked by anyone - the instance that held them is gone. A plain conditional update is both
     * correct and cheaper, and it stays correct if two instances run the sweep at once because the
     * {@code lease_until} predicate is re-evaluated by each of them.
     *
     * <p>{@code next_attempt_at} is set to now rather than to a backoff: the run has not failed, it has
     * been abandoned, and making a caller wait out a backoff for somebody else's crash would be a
     * penalty for the wrong event. The attempt was already counted when the dead instance claimed it,
     * which is what stops this from being an infinite loop.
     */
    @Override
    @Transactional
    public int reclaimExpired(Instant now, int limit) {
        return entityManager.createNativeQuery("""
                        UPDATE export_report_run SET
                            status = 'PENDING',
                            claimed_by = NULL,
                            claimed_at = NULL,
                            heartbeat_at = NULL,
                            lease_until = NULL,
                            next_attempt_at = :now
                        WHERE id IN (
                            SELECT t.id FROM export_report_run t
                            WHERE t.status = 'RUNNING'
                              AND t.lease_until IS NOT NULL
                              AND t.lease_until < :now
                            ORDER BY t.lease_until, t.id
                            LIMIT :limit
                        )
                        """)
                .setParameter("now", now)
                .setParameter("limit", limit)
                .executeUpdate();
    }

    /**
     * Renews a lease, and says whether it is still ours.
     *
     * <p>The owner is part of the predicate rather than merely written: a renewal that succeeded
     * regardless of who holds the row would let an instance whose lease had already been reclaimed
     * keep extending it, so two instances would be writing the same report and both would believe
     * they were entitled to. Returning false is the signal to stop immediately - see
     * {@code RunLock.renew} in {@code job-core}, which this mirrors for a row rather than for a
     * named lock.
     */
    @Override
    @Transactional
    public boolean renewLease(UUID runId, String owner, Instant now, Duration lease) {
        int updated = entityManager.createNativeQuery("""
                        UPDATE export_report_run SET
                            heartbeat_at = :now,
                            lease_until = :leaseUntil
                        WHERE id = :runId
                          AND claimed_by = :owner
                          AND status = 'RUNNING'
                        """)
                .setParameter("now", now)
                .setParameter("leaseUntil", now.plus(lease))
                .setParameter("runId", runId)
                .setParameter("owner", owner)
                .executeUpdate();
        return updated == 1;
    }
}
