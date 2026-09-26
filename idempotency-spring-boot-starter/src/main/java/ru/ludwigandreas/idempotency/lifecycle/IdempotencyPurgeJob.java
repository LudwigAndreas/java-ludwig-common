package ru.ludwigandreas.idempotency.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.store.PostgresIdempotencyStore;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Drops claims past their window, on one replica at a time.
 *
 * <h2>Why this module owns the purge rather than leaving it to the consumer</h2>
 *
 * <p>Because the TTL is a correctness parameter, and a module that ships one must ship the thing that
 * makes it true. {@code IdempotencyStore#purgeExpired}'s original javadoc, written when this primitive
 * lived in {@code notification-service}, already said the important half: "must run on exactly one
 * replica - it is one of the jobs the distributed lock exists for". At the time the lock was also in that
 * service; now it is {@code job-core}'s, so there is nothing left to leave to the consumer except the
 * decision to schedule it, which every consumer would get wrong in the same way.
 *
 * <p>Under {@code job-core}'s leased {@link RunLock}, modelled on {@code ExportRetentionPurge}. Three
 * replicas purging concurrently would not corrupt anything - the statements are idempotent deletes - but
 * they would take conflicting locks on the same pages of a table receiving an insert per protected
 * request, and two of the three would do nothing but contend.
 *
 * <p>Deliberately <b>not</b> a {@code SKIP LOCKED} claim over the rows, which is how the rest of this
 * platform partitions work across replicas. A purge is defined over a <em>set</em> of rows rather than over
 * each row independently, which is exactly the case {@code RunLock}'s own documentation names as needing a
 * lock: the useful unit is "everything expired as of now", and splitting that across three replicas buys
 * nothing because there is no per-row work to parallelise.
 *
 * <h2>Batches, and the lease between them</h2>
 *
 * <p>The delete is batched and the lease is renewed between batches, and a lost lease stops the run. Both
 * matter for the same reason: the first run after somebody shortens the TTL may have millions of rows to
 * drop, and one statement over all of them would pin the oldest transaction id, stop autovacuum on the
 * busiest table in the service, and hold locks across its hot path. Stopping half way is safe - the
 * batches are independent and the rest go on the next tick.
 */
@Slf4j
public class IdempotencyPurgeJob extends SelfSchedulingLifecycle {

    /** The name in the log lines, and the one to grep for. */
    private static final String JOB_NAME = "ludwig-idempotency-purge";

    /** How long {@code stop()} waits for an in-flight batch, which is a bounded delete. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final PostgresIdempotencyStore store;
    private final RunLock lock;
    private final IdempotencyProperties properties;
    private final IdempotencyMetrics metrics;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param taskScheduler the scheduler the job registers itself with
     * @param store         the store whose claims are purged
     * @param lock          the platform's leased distributed lock
     * @param properties    the configuration
     * @param metrics       what this module reports about itself
     * @param clock         the clock windows are judged against
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a constructor whose arguments are all injected beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IdempotencyPurgeJob(TaskScheduler taskScheduler, PostgresIdempotencyStore store, RunLock lock,
                               IdempotencyProperties properties, IdempotencyMetrics metrics, Clock clock) {
        super(JOB_NAME, taskScheduler,
                ScheduleSpec.fixedDelay(properties.getPurge().getInterval(),
                        properties.getPurge().getInitialDelay()),
                DRAIN_TIMEOUT);
        this.store = store;
        this.lock = lock;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        lock.runIfAvailable(properties.getPurge().getLockName(), this::purge);
    }

    /**
     * One run: batches of expired claims until there are none left, the batch budget is spent, or the
     * lease is lost.
     *
     * <p>{@code now} is read once, at the start, rather than per batch. A purge that re-read the clock
     * would keep finding newly expired rows and could run until the batch budget ran out on a busy
     * service - which turns a bounded maintenance job into an unbounded one, at whatever moment the
     * service is busiest.
     */
    private void purge(RunLockHandle held) {
        Instant now = clock.instant();
        IdempotencyProperties.Purge config = properties.getPurge();
        long total = 0;
        for (int batch = 0; batch < config.getMaxBatchesPerRun(); batch++) {
            if (!held.renew(lock.defaultLeaseTtl())) {
                log.warn("Idempotency purge stopped after {} claims: the lock was lost", total);
                break;
            }
            long purged = store.purgeBatch(now, config.getBatchSize());
            total += purged;
            if (purged < config.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            metrics.purged(total);
            log.info("Idempotency purge released {} expired claims", total);
        }
        long remaining = store.countExpired(now);
        if (remaining > 0) {
            // Said out loud because it is the one operational condition worth acting on here: a purge that
            // cannot keep up means the table grows, and the fix is a bigger batch or a shorter interval -
            // never a shorter TTL, which would convert duplicates into double executions.
            log.warn("Idempotency purge left {} expired claims for the next run; consider raising"
                    + " ludwig.idempotency.purge.batch-size or lowering the interval", remaining);
        }
    }
}
