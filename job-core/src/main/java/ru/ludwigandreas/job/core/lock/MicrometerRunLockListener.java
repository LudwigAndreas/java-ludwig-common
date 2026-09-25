package ru.ludwigandreas.job.core.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;

/**
 * Publishes {@link RunLock} outcomes to Micrometer.
 *
 * <p>The only class in {@code job-core} that touches Micrometer, which is why it is a separate
 * {@link RunLockListener} implementation rather than code inside {@link JdbcRunLock}: the dependency
 * is declared {@code optional} and this class is only loaded when the consumer already has it - see
 * {@code JobCoreAutoConfiguration}.
 *
 * <h2>The two meters</h2>
 *
 * <p>{@code ludwig.job.lock.acquisition}, tagged {@code lock} and {@code acquired}, answers "is this
 * job running at all?". Every replica contending and none acquiring, sustained, means the lease is
 * stranded on a pod that no longer exists.
 *
 * <p>{@code ludwig.job.lock.lost} counts failed renewals, and is the one worth an alert. A lost
 * renewal means a run was superseded while it was still working: either the lease is too short for
 * the job or the process stalled, and in both cases some part of the work has been done twice or
 * abandoned half done. Neither of this platform's two previous lock implementations recorded it,
 * which meant the one event anybody needed to page on was the one nothing measured.
 *
 * <p>Counters are resolved per call rather than cached per lock name: the set of lock names is small
 * but open, and a map of pre-built counters would either be unbounded or need eviction, to save an
 * operation Micrometer already makes cheap.
 */
public class MicrometerRunLockListener implements RunLockListener {

    /** Attempts to take a lease, tagged by lock name and by whether the attempt succeeded. */
    public static final String ACQUISITION_COUNTER = "ludwig.job.lock.acquisition";

    /** Renewals that found the lease no longer held by this run. */
    public static final String LOST_COUNTER = "ludwig.job.lock.lost";

    private final MeterRegistry registry;

    /**
     * Creates the binding.
     *
     * @param registry the registry meters are published to
     */
    public MicrometerRunLockListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onAcquired(String lockName, String owner, Duration leaseTtl) {
        acquisition(lockName, true);
    }

    @Override
    public void onContended(String lockName, String owner) {
        acquisition(lockName, false);
    }

    @Override
    public void onLost(String lockName, String owner) {
        Counter.builder(LOST_COUNTER).tags(Tags.of("lock", lockName)).register(registry).increment();
    }

    private void acquisition(String lockName, boolean acquired) {
        // The owner is deliberately not a tag. It carries a random suffix, so it is unbounded in
        // cardinality across restarts - it belongs in a log line, where it is, and not in a series.
        Counter.builder(ACQUISITION_COUNTER)
                .tags(Tags.of("lock", lockName, "acquired", Boolean.toString(acquired)))
                .register(registry)
                .increment();
    }
}
