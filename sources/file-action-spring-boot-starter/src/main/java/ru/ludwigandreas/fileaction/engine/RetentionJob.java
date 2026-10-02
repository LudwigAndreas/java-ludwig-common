package ru.ludwigandreas.fileaction.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.fileaction.audit.FileActionAuditActions;
import ru.ludwigandreas.fileaction.audit.FileActionAuditEvent;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * Deletes the stored bytes of submissions whose window has closed.
 *
 * <h2>Why retention is the module's job and not the bucket's</h2>
 *
 * <p>A bucket lifecycle rule would delete the object and leave the submission row claiming a result that is no
 * longer there - so a poll would answer {@code SUCCEEDED} with a link to nothing, which is exactly the outcome
 * {@code OperationStatus.EXPIRED} exists to prevent. Deleting both together, in this order, is what makes the
 * envelope honest.
 *
 * <h2>Why this one holds a RunLock and the worker does not</h2>
 *
 * <p>Retention is housekeeping with no per-item parallelism to gain: several instances deleting the same
 * submission's objects at once would each issue a delete and each succeed - {@code ObjectStore} makes deleting
 * something absent a success - but they would also each write the row, and the wasted work scales with the size
 * of the cluster. One holder at a time is simply correct here. The deferred worker is the opposite case:
 * instances processing different submissions concurrently is the point.
 */
public class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    /** The platform's one distributed lock, under this name. */
    public static final String LOCK_NAME = "file-action-retention";

    private final FileActionSubmissionRepository submissions;
    private final SubmissionStore store;
    private final ObjectStore objectStore;
    private final RunLock runLock;
    private final AuditSink audit;
    private final int batchSize;
    private final Duration lease;
    private final Clock clock;

    /**
     * Wires the job.
     *
     * @param submissions the submission table
     * @param store       the state transitions
     * @param objectStore where the bytes are
     * @param runLock     the platform's one distributed lock
     * @param audit       the platform's one audit sink
     * @param batchSize   how many submissions one pass collects
     * @param lease       how long the lock is held
     * @param clock       the clock
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a job with a lock, a sink, two tables and two tuning values.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public RetentionJob(FileActionSubmissionRepository submissions, SubmissionStore store,
                        ObjectStore objectStore, RunLock runLock, AuditSink audit, int batchSize,
                        Duration lease, Clock clock) {
        this.submissions = submissions;
        this.store = store;
        this.objectStore = objectStore;
        this.runLock = runLock;
        this.audit = audit;
        this.batchSize = batchSize;
        this.lease = lease;
        this.clock = clock;
    }

    /** One pass, if this instance can take the lock. */
    public void pollOnce() {
        runLock.runIfAvailable(LOCK_NAME, lease, handle -> collect());
    }

    private void collect() {
        List<FileActionSubmissionEntity> expired = submissions.findExpired(clock.instant(), batchSize);
        for (FileActionSubmissionEntity submission : expired) {
            // The objects first, then the row. The other order would leave a row saying its artifacts are gone
            // while they are still in the bucket, which is a leak nothing would ever notice; this order can at
            // worst leave an orphaned object that the next pass does not revisit, which is visible in a bucket
            // listing and is the lesser of the two.
            delete(submission.getObjectUri());
            delete(submission.getBoundRowsUri());
            delete(submission.getErrorReportUri());
            store.markExpired(submission.getId());
            audit.record(FileActionAuditEvent.builder()
                    .action(submission.getAction())
                    .submissionId(submission.getId())
                    .auditAction(FileActionAuditActions.EXPIRED)
                    .actor(Actor.system())
                    .filename(submission.getDeclaredFilename())
                    .contentSha256(submission.getContentSha256())
                    .sizeBytes(submission.getSizeBytes())
                    .correlationId(submission.getCorrelationId())
                    .occurredAt(clock.instant())
                    .succeeded(true)
                    .build()
                    .toAuditEvent());
        }
        if (!expired.isEmpty()) {
            LOG.info("retention removed the stored artifacts of {} file-action submissions", expired.size());
        }
    }

    private void delete(String uri) {
        if (uri == null) {
            return;
        }
        try {
            objectStore.delete(uri);
        } catch (RuntimeException failed) {
            // One object that will not delete must not stop the pass: the remaining submissions are still due,
            // and the next pass will not see this one again because the row is about to be marked EXPIRED. The
            // orphan is logged so it is findable.
            LOG.warn("could not delete {} during retention; it is now orphaned", uri, failed);
        }
    }
}
