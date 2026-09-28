package ru.ludwigandreas.job.core.lock;

import java.time.Duration;

/**
 * Observes what happens to a {@link RunLock}'s leases.
 *
 * <h2>Why a listener rather than a {@code MeterRegistry} on the lock</h2>
 *
 * <p>Instrumenting the lock directly would put Micrometer on the path of every consumer of this
 * module, including the ones that have no metrics at all - and the alternative, a nullable
 * {@code MeterRegistry} constructor parameter, spreads null checks through a class whose whole job is
 * to be obviously correct. A no-op listener keeps {@link JdbcRunLock} free of any observability
 * dependency and free of any branch: it always has a listener, and by default that listener does
 * nothing. The Micrometer binding is one implementation of this interface, registered only when the
 * consumer actually has Micrometer - see {@code JobCoreAutoConfiguration}.
 *
 * <p>Every method has a no-op default, so an implementation observes only what it cares about and a
 * later callback added here does not break it. Implementations are called on the thread that is
 * acquiring, renewing or releasing the lease, so they must be quick and must not throw - a listener
 * that fails would turn an observability concern into a lost run.
 */
public interface RunLockListener {

    /** A listener that observes nothing, used whenever no instrumentation is registered. */
    RunLockListener NOOP = new RunLockListener() {
    };

    /**
     * A lease was taken.
     *
     * @param lockName the lock that was taken
     * @param owner    the instance that took it
     * @param leaseTtl how long it is good for without a renewal
     */
    default void onAcquired(String lockName, String owner, Duration leaseTtl) {
    }

    /**
     * A lease was not taken, because a live one is held elsewhere or the attempt itself failed.
     *
     * <p>Not an error: on a three-replica deployment two of the three are expected to be told this on
     * every tick. What matters is the ratio - every replica contending and none acquiring means the
     * lease is stranded on a pod that is gone.
     *
     * @param lockName the lock that could not be taken
     * @param owner    the instance that tried
     */
    default void onContended(String lockName, String owner) {
    }

    /**
     * A renewal failed, so the holder no longer owns the run it is part-way through.
     *
     * <p>This is the event worth alerting on. It means a run was superseded mid-flight - either the
     * lease was too short for the work, or the process stalled long enough to lose it - and the same
     * work is now being done twice or was abandoned half done.
     *
     * @param lockName the lock that was lost
     * @param owner    the instance that lost it
     */
    default void onLost(String lockName, String owner) {
    }

    /**
     * A lease was given back, whether the work succeeded or threw.
     *
     * @param lockName the lock that was released
     * @param owner    the instance that held it
     */
    default void onReleased(String lockName, String owner) {
    }
}
