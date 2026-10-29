package ru.ludwigandreas.reconciliation.engine;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.repository.SyncRunRepository;

/**
 * Keeps run history bounded and honest.
 *
 * <p>Two things each pass, under one run lock so exactly one replica does them:
 *
 * <ol>
 *   <li><b>Ends abandoned runs.</b> A run's lease is its run timeout, so a run still unfinished well past that has no
 *       replica behind it - the one that held it died. It is ended {@code FAILURE} with
 *       {@link RunFailureCodes#RUN_ABANDONED}; without this it would read as running for ever.</li>
 *   <li><b>Purges finished runs past retention</b>, in bounded batches, each committed on its own.</li>
 * </ol>
 *
 * <p>Not audited per batch: run history is operational state, not an audit trail - the run events themselves are in
 * the audit trail and are kept by its own retention.
 */
public class RunPurge extends SelfSchedulingLifecycle {

    /** The name this job's log lines, its lock and the jobs listing carry. */
    public static final String JOB_NAME = "ludwig-reconciliation-run-purge";

    /** How far past its run timeout an unfinished run must be before it is judged abandoned. */
    static final Duration ABANDON_MARGIN = Duration.ofMinutes(5);

    /** How long shutdown waits for an in-flight purge. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RunPurge.class);

    private final SyncRunRepository runs;
    private final TaskRegistry registry;
    private final ReconciliationProperties.Runs settings;
    private final RunLock lock;

    /**
     * Creates the job.
     *
     * @param taskScheduler the scheduler this job registers itself with
     * @param runs          the run table
     * @param registry      the tasks, for each one's run timeout
     * @param settings      the run-history settings, already validated
     * @param lock          the platform's distributed lock
     */
    public RunPurge(TaskScheduler taskScheduler, SyncRunRepository runs, TaskRegistry registry,
                    ReconciliationProperties.Runs settings, RunLock lock) {
        super(JOB_NAME, taskScheduler, ScheduleSpec.fixedDelay(settings.getPurgeInterval()), DRAIN_TIMEOUT);
        this.runs = runs;
        this.registry = registry;
        this.settings = settings;
        this.lock = lock;
    }

    @Override
    protected void runOnce() {
        lock.tryAcquire(JOB_NAME, settings.getPurgeInterval().multipliedBy(2)).ifPresent(this::purgeUnder);
    }

    private void purgeUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            Instant now = Instant.now();
            registry.all().forEach(task -> {
                long abandoned = runs.abandonUnfinishedBefore(task.settings().name(),
                        now.minus(task.settings().runTimeout()).minus(ABANDON_MARGIN), RunFailureCodes.RUN_ABANDONED,
                        now);
                if (abandoned > 0) {
                    log.warn("Ended {} abandoned run(s) of task '{}'", abandoned, task.settings().name());
                }
            });
            Instant cutoff = now.minus(settings.getRetention());
            long deleted;
            do {
                deleted = runs.purgeFinishedBefore(cutoff, settings.getPurgeBatchSize());
                if (deleted > 0) {
                    log.info("Run history purge deleted {} run(s) finished before {}", deleted, cutoff);
                }
            } while (deleted == settings.getPurgeBatchSize());
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - only the handle's close can throw here that the passes have not already
            // let out, and failing to release a leased lock is not worth failing the job over: the lease expires.
            log.warn("Could not release the run history purge lock: {}", e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}
