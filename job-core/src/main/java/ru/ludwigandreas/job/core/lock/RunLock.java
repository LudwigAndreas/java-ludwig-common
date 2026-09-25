package ru.ludwigandreas.job.core.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A cluster-wide mutual exclusion for a named unit of scheduled work, so that N replicas of a service
 * running the same schedule produce one run rather than N.
 *
 * <p>The platform's only distributed lock. Anything that needs "this runs on exactly one replica"
 * takes it from here rather than inventing a second one - two lock implementations in one codebase
 * are two tables, two fencing rules and two sets of operational behaviour to learn, and nothing is
 * ever coordinated between them.
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
 *
 * <h2>What it is not for</h2>
 *
 * <p>Not for work that is already partitioned by its own data access. A poller built on
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} - see {@code SkipLockedClaim} - hands disjoint batches to
 * every replica with no coordination at all, and wrapping a lock around it throws away all but one
 * replica's throughput to solve a problem that does not exist. Reaching for a lock there is the
 * classic way a horizontally-scalable queue becomes a single-threaded one. What needs a lock is work
 * defined over a <em>set</em> of rows rather than over each row independently: a digest that collapses
 * many rows into one message, a retention purge, a compaction over an expiry boundary.
 */
public interface RunLock {

    /**
     * The lease used by an implementation that has no configured default.
     *
     * <p>Long enough that a garbage-collection pause or a slow statement cannot cost a running job
     * its lease, short enough that a pod killed while holding one costs a single missed window. A
     * deployment moves it with {@code ludwig.job-core.lock.default-lease}; a job whose runs are
     * nothing like this length passes its own TTL instead of moving the number for everyone.
     */
    Duration FALLBACK_LEASE = Duration.ofMinutes(5);

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

    /**
     * The lease length used by {@link #runIfAvailable(String, Consumer)}.
     *
     * <p>Exists so that a caller with no opinion about the lease does not have to invent one at the
     * call site. A TTL hard-coded next to a scheduled job is a number an operator cannot change
     * during an incident, and jobs that each picked their own would give the deployment several
     * different failover times for no reason - see {@code ludwig.job-core.lock.default-lease}.
     *
     * @return the configured default, or {@link #FALLBACK_LEASE} for an implementation that has none
     */
    default Duration defaultLeaseTtl() {
        return FALLBACK_LEASE;
    }

    /**
     * Runs {@code work} under the lease on {@code lockName}, and does nothing at all if the lease is
     * held elsewhere.
     *
     * <p>The form most call sites want: acquiring, releasing and the "did not get it" branch are the
     * same three lines everywhere, and writing them by hand is how one job eventually forgets the
     * release. A {@code default} method rather than a second implementation so that there is exactly
     * one of it and it cannot drift from {@link #tryAcquire}.
     *
     * <p>Not acquiring is a normal outcome rather than a failure: on a three-replica deployment two of
     * the three are expected to skip every run.
     *
     * <p>The lease is released in a {@code finally}, so it is given back <em>even when {@code work}
     * throws</em>. Letting it expire instead would also be correct, but it would block the next
     * scheduled run for a full lease period after a failure that took milliseconds - so a crashed pod
     * costs one lease and a thrown exception costs nothing. The exception itself propagates unchanged:
     * deciding whether a failed run should stop the schedule is the caller's, not the lock's.
     *
     * @param lockName logical name of the work being serialized
     * @param leaseTtl how long the lease is good for without a renewal
     * @param work     the run, handed the handle so that a long job can renew its lease; a renewal
     *                 that returns {@code false} means this replica has been superseded and the work
     *                 must stop immediately
     * @return whether the lease was taken and {@code work} ran
     */
    default boolean runIfAvailable(String lockName, Duration leaseTtl, Consumer<RunLockHandle> work) {
        Optional<RunLockHandle> acquired = tryAcquire(lockName, leaseTtl);
        if (acquired.isEmpty()) {
            return false;
        }
        try (RunLockHandle held = acquired.get()) {
            work.accept(held);
            return true;
        }
    }

    /**
     * Runs {@code work} under a lease of {@link #defaultLeaseTtl()}.
     *
     * @param lockName logical name of the work being serialized
     * @param work     the run; see {@link #runIfAvailable(String, Duration, Consumer)}
     * @return whether the lease was taken and {@code work} ran
     */
    default boolean runIfAvailable(String lockName, Consumer<RunLockHandle> work) {
        return runIfAvailable(lockName, defaultLeaseTtl(), work);
    }
}
