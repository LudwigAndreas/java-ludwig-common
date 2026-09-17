package ru.ludwigandreas.notification.service.lock;

import java.util.function.Consumer;

/**
 * Runs a job on exactly one replica.
 *
 * <h2>Why this exists here</h2>
 *
 * <p>The second of the two capabilities the platform does not have yet, and, like the idempotency
 * store, a narrow interface precisely so it can be promoted into a starter without touching a call
 * site - see the README's "Promotion candidates".
 *
 * <p>What it is <em>not</em> for is the delivery poller. {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * already partitions the queue between replicas, so three pollers claim disjoint batches with no
 * coordination at all, and putting a lock around them would throw away two thirds of the throughput
 * to solve a problem that does not exist. Reaching for a lock there is the classic way a
 * horizontally-scalable queue becomes a single-threaded one.
 *
 * <p>What it <em>is</em> for is every job that is not partitioned by its own data access: the digest
 * collapse (three replicas, three digests, one recipient), the retention purge (wasteful and
 * lock-contending), and the suppression compaction (interleaving deletes over an expiry boundary).
 * Those are exactly-once-per-schedule or they are wrong.
 */
public interface DistributedLock {

    /**
     * Runs {@code work} if this replica can take {@code lockName}, and does nothing if it cannot.
     *
     * <p>Not acquiring is a normal outcome, not a failure: on a three-replica deployment two of the
     * three are expected to skip every run. Implementations must not block waiting for the lock - a
     * queued job would run three times in a row rather than once.
     *
     * <p>The handle passed to {@code work} is how a long job keeps its lease alive. A job that does
     * not call {@link LockHandle#renew()} and outruns the lease loses the lock to another replica
     * mid-run, which is worse than not running at all.
     *
     * @return whether the lock was taken and the work ran
     */
    boolean runIfLockAvailable(String lockName, Consumer<LockHandle> work);

    /** A held lease, and the two things a running job can do with it. */
    interface LockHandle {

        /**
         * Extends the lease.
         *
         * @return {@code false} if this replica no longer holds the lock, which means another
         *         replica has taken over and the job must stop immediately rather than continue
         *         doing work a second process is now also doing
         */
        boolean renew();

        /** The lock's name, for logging. */
        String name();
    }
}
