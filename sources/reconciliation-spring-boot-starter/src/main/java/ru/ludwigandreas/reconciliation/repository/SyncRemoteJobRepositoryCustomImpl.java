package ru.ludwigandreas.reconciliation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.job.core.claim.SkipLockedClaim;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Found by Spring Data through the {@code ...Impl} naming convention. */
public class SyncRemoteJobRepositoryCustomImpl implements SyncRemoteJobRepositoryCustom {

    private static final String TABLE = "sync_remote_job";

    private static final String ORDER_BY = "t.next_poll_at, t.id";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public List<SyncRemoteJob> claimForPoll(String taskName, int limit, Instant now, String owner) {
        return SkipLockedClaim.claim(entityManager, SyncRemoteJob.class, TABLE,
                "owner_instance = :owner, last_polled_at = now()",
                "t.task_name = :taskName AND t.state IN ('SUBMITTED', 'RUNNING') "
                        + "AND (t.next_poll_at IS NULL OR t.next_poll_at <= :now)",
                ORDER_BY, limit,
                Map.of("taskName", taskName, "owner", owner, "now", now));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Moves the job to {@code COLLECTING} as part of the claim. That state is what stops a second
     * instance from starting the same collection, and it is resumable - {@code collect_cursor} says
     * where this one got to - so an instance that dies mid-collection costs the pages it had not
     * reached yet, not the whole result.
     */
    @Override
    @Transactional
    public List<SyncRemoteJob> claimForCollect(String taskName, int limit, String owner) {
        return SkipLockedClaim.claim(entityManager, SyncRemoteJob.class, TABLE,
                "state = 'COLLECTING', owner_instance = :owner",
                "t.task_name = :taskName AND t.state IN ('SUCCEEDED', 'COLLECTING')",
                "t.created_at, t.id", limit,
                Map.of("taskName", taskName, "owner", owner));
    }
}
