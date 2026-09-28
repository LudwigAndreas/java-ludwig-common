package ru.ludwigandreas.export.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Claims deferred report runs and hands them to the executor.
 *
 * <h2>Claiming only what it can actually start</h2>
 *
 * <p>A permit is taken <em>before</em> the claim, not after. A report run holds a thread for minutes,
 * so a poller that claimed a batch and then discovered it had capacity for one of them would leave
 * the rest marked RUNNING and idle - invisible to every other instance until their leases expired.
 * Claiming against available capacity means a run that is claimed is a run that has somewhere to go.
 *
 * <p>This is also why the claim batch size defaults to one here where the outbox's is larger: the
 * outbox dispatches in milliseconds, so a batch amortises a round trip. A report does not.
 *
 * <h2>Reclaiming before claiming</h2>
 *
 * <p>Each tick first returns expired leases to PENDING, then claims. In that order, because the
 * reclaimed rows become claimable in the same tick rather than waiting for the next one - which on a
 * long poll interval is the difference between a crashed instance's report resuming in seconds and
 * resuming when somebody notices.
 *
 * <h2>Exceptions propagate</h2>
 *
 * <p>{@code runOnce} lets everything out, as {@code SelfSchedulingLifecycle} asks: the base class
 * logs which job failed and schedules the next tick. Catching here would cost exactly that log line
 * and gain nothing, since a poller has no other way to react to its own failure.
 */
@Slf4j
public class ExportRunPoller extends SelfSchedulingLifecycle {

    /** The name this poller's log lines carry, so an operator can grep for one string. */
    public static final String JOB_NAME = "ludwig-export-run-poller";

    private final ExportReportRunRepository runs;
    private final ExportRunExecutor executor;
    private final ExportProperties properties;
    private final JobInstanceIdentity identity;
    private final Clock clock;
    private final ExecutorService workers;

    /**
     * How many runs this instance may execute at once.
     *
     * <p>A semaphore rather than the worker pool's own queue, because a bounded queue would accept
     * work it could not start and an unbounded one would accept work nothing could. The permit is
     * what makes "claim only what you can run" expressible at all.
     */
    private final Semaphore capacity;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ExportRunPoller(TaskScheduler taskScheduler, ExportReportRunRepository runs,
                           ExportRunExecutor executor, ExportProperties properties,
                           JobInstanceIdentity identity, Clock clock, ExecutorService workers) {
        super(JOB_NAME, taskScheduler,
                new ScheduleSpec(properties.getPoller().getInterval(), null,
                        properties.getPoller().getInterval()),
                properties.getPoller().getDrainTimeout());
        this.runs = runs;
        this.executor = executor;
        this.properties = properties;
        this.identity = identity;
        this.clock = clock;
        this.workers = workers;
        this.capacity = new Semaphore(properties.getPoller().getConcurrency());
    }

    @Override
    protected void runOnce() {
        reclaimExpired();
        int free = capacity.availablePermits();
        if (free == 0) {
            return;
        }
        int limit = Math.min(free, properties.getPoller().getClaimBatchSize());
        List<ExportReportRun> claimed = runs.claimDue(identity.owner(), clock.instant(),
                properties.getPoller().getLeaseDuration(), limit);
        for (ExportReportRun run : claimed) {
            start(run);
        }
    }

    private void reclaimExpired() {
        int reclaimed = runs.reclaimExpired(clock.instant(), properties.getPoller().getClaimBatchSize());
        if (reclaimed > 0) {
            // Worth an INFO rather than a DEBUG: every one of these is a run whose instance died
            // mid-report, which is a thing an operator wants to see happening even when the recovery
            // works, because it working is not the same as it being normal.
            log.info("Reclaimed {} report run(s) whose lease had expired", reclaimed);
        }
    }

    /**
     * Hands one claimed run to a worker.
     *
     * <p>The permit is released by the worker rather than here, so it is held for the whole run and
     * not merely for the submission. A permit released on submit would let the next tick claim
     * against capacity that is already occupied, which is the same bug as claiming a batch.
     */
    private void start(ExportReportRun run) {
        if (!capacity.tryAcquire()) {
            // Another tick took the last permit between the availablePermits() check and here. The
            // run stays claimed and leased; when this instance does not heartbeat it, the lease
            // expires and somebody picks it up - which is exactly the recovery path a crash uses.
            log.debug("No capacity left for report run {}; leaving it to the lease", run.getId());
            return;
        }
        workers.execute(() -> {
            try {
                executor.execute(run);
            } finally {
                capacity.release();
            }
        });
    }

    /** The lease this poller takes, for a test or an actuator that wants to report it. */
    public Duration leaseDuration() {
        return properties.getPoller().getLeaseDuration();
    }
}
