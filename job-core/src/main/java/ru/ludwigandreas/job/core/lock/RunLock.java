package ru.ludwigandreas.job.core.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * A cluster-wide mutual exclusion for a named unit of scheduled work, so that N replicas of a service
 * running the same schedule produce one run rather than N.
 *
 * <h2>Why a lease and not a lock</h2>
 *
 * <p>A lock held by a process that has died is a lock held forever, and "the job silently stopped
 * running after a pod was killed" is not a failure anything alerts on - the schedule keeps firing,
 * every tick declines to run, and the backlog grows behind a metric nobody is watching. Every
 * acquisition therefore carries an expiry, and a holder that wants to keep it past that expiry has to
 * keep saying so with {@link RunLockHandle#renew(Duration)}. A dead holder stops renewing and the
 * lease becomes claimable on its own.
 *
 * <p>The consequence, which callers must design for: holding a lease is never proof that you still
 * hold it. {@code renew} returns whether the lease is still yours, and a holder whose renewal fails
 * has been superseded and must stop working immediately rather than finish the batch.
 */
public interface RunLock {

    /**
     * Attempts to take the lease on {@code lockName} without waiting.
     *
     * <p>Non-blocking by design: a scheduled job that cannot get the lease has nothing useful to do
     * but come back on its next tick, and blocking would pin a scheduler thread for as long as
     * another instance's run takes.
     *
     * @param lockName logical name of the work being serialized, typically the task name
     * @param leaseTtl how long the lease is good for without a renewal; must comfortably exceed the
     *                 interval at which the holder renews, or a single slow renewal loses the lease
     *                 to another instance mid-run
     * @return the handle if this instance took the lease, empty if a live lease is held elsewhere
     */
    Optional<RunLockHandle> tryAcquire(String lockName, Duration leaseTtl);
}
