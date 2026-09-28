package ru.ludwigandreas.reconciliation.quota;

import java.util.Optional;
import java.util.UUID;

/**
 * A partner-scoped concurrency budget.
 *
 * <p>The unit of counting is a <em>slot</em>, held for as long as the work it covers is running at the
 * partner - which for an asynchronous job is minutes or hours, across scheduler ticks and process
 * restarts. That is what makes this different from a bulkhead, and why it is in the database.
 */
public interface Quota {

    /**
     * Tries to take a slot.
     *
     * <p>Non-blocking unless the quota's {@code acquire-timeout} says otherwise, and the default is
     * not to wait: a pass that cannot get a slot has nothing useful to do but come back next tick, and
     * waiting pins a scheduler thread for as long as a saturated partner stays saturated.
     *
     * <p>A caller that misses is left in the queue, so that its next attempt keeps the position it has
     * already waited for - which is what makes {@code fifo} mean anything across ticks.
     *
     * @param quotaName the quota
     * @param taskName  the task asking
     * @param jobId     the remote job the slot is for, or null when it is for a synchronous pass
     * @return the handle, or empty if no slot was available
     */
    Optional<QuotaLeaseHandle> tryAcquire(String quotaName, String taskName, UUID jobId);

    /**
     * Re-attaches to a lease this instance did not create, after adopting the job it was held for.
     *
     * <p>The counterpart of "adopt, never restart": a restarted pod takes over the running job and
     * resumes heartbeating its existing lease rather than releasing it and taking a new slot, which
     * would briefly count one job twice.
     *
     * @param leaseId the lease recorded on the job
     * @return the handle, or empty if that lease has since expired and been reclaimed
     */
    Optional<QuotaLeaseHandle> adopt(UUID leaseId);

    /**
     * How many slots of a quota are currently held.
     *
     * @param quotaName the quota
     * @return live slots
     */
    long inFlight(String quotaName);

    /**
     * How long the longest-waiting task has been queued, in seconds.
     *
     * @param quotaName the quota
     * @return the wait, or zero when nobody is waiting
     */
    double oldestWaiterAgeSeconds(String quotaName);
}
