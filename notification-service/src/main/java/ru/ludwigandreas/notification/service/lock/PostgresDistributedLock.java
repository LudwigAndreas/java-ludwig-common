package ru.ludwigandreas.notification.service.lock;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;

/**
 * A leased row in {@code notification_lock}.
 *
 * <h2>Why not {@code pg_try_advisory_lock}</h2>
 *
 * <p>An advisory lock is held by the database <em>session</em>. With a connection pool the session
 * goes back to the pool the instant the statement finishes, so holding one across a multi-minute
 * digest run means pinning a pooled connection for the duration and trusting that nothing in the
 * stack quietly returns it - and if anything does, the lock silently vanishes while the job is still
 * running. It is also invisible: an operator asking "why has the digest not run for an hour?" has
 * nothing to look at.
 *
 * <p>A row with an explicit lease is inspectable, survives the connection, fails over on a configured
 * timeout rather than an accidental one, and can be broken by hand when something has genuinely gone
 * wrong.
 *
 * <h2>The lease is the correctness argument</h2>
 *
 * <p>A pod that dies holding the lock blocks the job for one lease period and no longer. A job that
 * legitimately runs longer than a lease renews it; a job that stops renewing has stopped, whether it
 * knows it or not. The one thing a job must do is honour a failed {@link LockHandle#renew()} by
 * stopping - continuing past it means two replicas are in the same job, which is the situation the
 * lock existed to prevent.
 *
 * <p>Registered as a plain bean from {@code NotificationQueueConfig} rather than annotated
 * {@code @Service}, because it needs the resolved instance id, which is not a bean of its own.
 */
@Slf4j
public class PostgresDistributedLock implements DistributedLock {

    private final LockLeaseService leases;
    private final NotificationMetrics metrics;
    private final String owner;

    public PostgresDistributedLock(LockLeaseService leases, NotificationMetrics metrics, String owner) {
        this.leases = leases;
        this.metrics = metrics;
        this.owner = owner;
    }

    @Override
    public boolean runIfLockAvailable(String lockName, Consumer<LockHandle> work) {
        if (!leases.tryAcquire(lockName, owner)) {
            metrics.recordLockAcquisition(lockName, false);
            log.debug("Lock {} is held elsewhere; skipping this run", lockName);
            return false;
        }
        metrics.recordLockAcquisition(lockName, true);
        try {
            work.accept(new Handle(lockName));
            return true;
        } finally {
            // Released even when the job threw. Leaving the lease to expire would be correct but
            // would block the next scheduled run for a full lease period after a failure that took
            // milliseconds - so a crash costs one lease and a thrown exception costs nothing.
            releaseQuietly(lockName);
        }
    }

    private void releaseQuietly(String lockName) {
        try {
            leases.release(lockName, owner);
        } catch (RuntimeException e) {
            // Not fatal: the lease expires on its own, so a failed release costs one lease period of
            // delay rather than a stuck job. Worth a warning, because a release that keeps failing
            // means the database is in trouble and the next run will be too.
            log.warn("Could not release lock {}; it will expire on its own", lockName, e);
        }
    }

    /** The handle a running job holds. */
    private final class Handle implements LockHandle {

        private final String lockName;

        private Handle(String lockName) {
            this.lockName = lockName;
        }

        @Override
        public boolean renew() {
            boolean renewed = leases.renew(lockName, owner);
            if (!renewed) {
                log.warn("Lost lock {} mid-run - another replica has taken it; stopping", lockName);
            }
            return renewed;
        }

        @Override
        public String name() {
            return lockName;
        }
    }
}
