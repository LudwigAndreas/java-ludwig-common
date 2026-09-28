package ru.ludwigandreas.reconciliation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.job.core.claim.SkipLockedClaim;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Found by Spring Data through the {@code ...Impl} naming convention; needs no annotation and no
 * registration.
 */
public class SyncInboxRecordRepositoryCustomImpl implements SyncInboxRecordRepositoryCustom {

    private static final String TABLE = "sync_inbox_record";

    private static final String CLAIM_SET_CLAUSE =
            "status = 'PROCESSING', locked_at = now(), locked_by = :owner";

    /**
     * Only RECORD and MISSING rows are the apply pass's business. A FETCH_FAILURE row is a backoff
     * ticket for the fetch pass, and claiming it here would be an apply pass repeatedly picking up a
     * row with nothing in it to apply.
     */
    private static final String DUE_PREDICATE =
            "t.task_name = :taskName AND t.kind IN ('RECORD', 'MISSING') "
                    + "AND t.status IN ('STAGED', 'FAILED', 'DEFERRED') "
                    + "AND t.next_attempt_at <= :now";

    private static final String ORDER_BY = "t.received_at, t.id";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * <p>Read-write and in its own short transaction: the claim has to be committed and visible to
     * every other instance before this one starts applying, or two instances apply the same record.
     */
    @Override
    @Transactional
    public List<SyncInboxRecord> claimForApply(String taskName, int batchSize, Instant now, String owner) {
        return SkipLockedClaim.claim(entityManager, SyncInboxRecord.class, TABLE,
                CLAIM_SET_CLAUSE, DUE_PREDICATE, ORDER_BY, batchSize,
                Map.of("taskName", taskName, "owner", owner, "now", now));
    }
}
