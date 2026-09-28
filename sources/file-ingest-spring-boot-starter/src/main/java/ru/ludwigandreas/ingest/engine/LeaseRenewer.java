package ru.ludwigandreas.ingest.engine;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.exception.LeaseLostException;
import ru.ludwigandreas.job.core.lock.RunLockHandle;

/**
 * Keeps the run's lock alive while the run is reading, and stops the run the moment it cannot.
 *
 * <h2>Why the renewal is inside the batch loop</h2>
 *
 * <p>A forty-minute ingest under a five-minute lease that is renewed only when the run starts has lost
 * the lock by minute six. Nothing tells it so - a lease is not a connection, it simply expires - and
 * from minute six onward a second replica is free to take the lock and begin importing the same object
 * into the same staging table. Both runs then advance their own checkpoints against the same file, and
 * the result is every record written twice, discovered when somebody notices the target has roughly
 * double the rows it should.
 *
 * <p>So the lease is renewed between batches, which is the only place in the loop where the run is at
 * a consistent point. Renewing mid-batch would buy nothing: the batch is bounded in both records and
 * bytes precisely so that it is short.
 *
 * <h2>Why a failed renewal stops the run immediately, mid-file</h2>
 *
 * <p>Because the asymmetry is enormous. Stopping costs the work since the last checkpoint - at most
 * one batch, because the next run resumes from exactly where this one committed. Continuing risks
 * every remaining record being written twice by two replicas that do not know about each other. There
 * is no amount of remaining work that makes the second trade worth taking, which is why this throws
 * rather than logging a warning and carrying on.
 *
 * <h2>Why renewal happens before the margin runs out</h2>
 *
 * <p>Renewing exactly when the lease expires is renewing too late: the statement takes time, and a
 * slow database is the situation in which the lease is most likely to be about to lapse. The margin is
 * a fraction of the lease rather than a fixed duration, so that changing the lease does not silently
 * change the safety margin with it.
 */
@Slf4j
public class LeaseRenewer {

    private final RunLockHandle handle;
    private final Duration lease;
    private final Duration renewAfter;
    private final String task;

    private Instant lastRenewedAt;

    /**
     * Creates the renewer for one run.
     *
     * @param handle the held lock
     * @param task   the task name, for the failure message
     * @param lock   the task's lock settings
     * @param now    when the lock was acquired
     */
    public LeaseRenewer(RunLockHandle handle, String task, FileIngestProperties.Lock lock, Instant now) {
        this.handle = handle;
        this.task = task;
        this.lease = lock.getLease();
        long renewAfterMillis = (long) (lease.toMillis() * (1.0 - lock.getRenewAtRemainingFraction()));
        this.renewAfter = Duration.ofMillis(Math.max(renewAfterMillis, 1));
        this.lastRenewedAt = now;
    }

    /**
     * Renews the lease if enough of it has been used, and stops the run if the renewal is refused.
     *
     * <p>Cheap to call after every batch: it compares two instants and usually returns. That is
     * deliberate - a renewer the caller has to decide when to call is a renewer somebody will call
     * from the wrong place.
     *
     * @param now              the current instant
     * @param recordsCommitted how far the run has got, for the failure message
     * @throws LeaseLostException if the lease could not be renewed
     */
    public void renewIfDue(Instant now, long recordsCommitted) {
        if (Duration.between(lastRenewedAt, now).compareTo(renewAfter) < 0) {
            return;
        }
        if (!handle.renew(lease)) {
            throw new LeaseLostException(task, recordsCommitted);
        }
        lastRenewedAt = now;
        log.debug("Renewed the run lock for task {} after {} committed records", task, recordsCommitted);
    }
}
