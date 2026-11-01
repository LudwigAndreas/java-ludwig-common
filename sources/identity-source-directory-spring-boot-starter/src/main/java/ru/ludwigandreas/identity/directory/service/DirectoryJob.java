package ru.ludwigandreas.identity.directory.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * One source's sync or sweep as a platform job: {@code ludwig-identity-<source>-sync} or {@code -sweep}, under the
 * run lock of the same name so at most one replica runs it, listed by {@code GET /admin/v1/jobs} and runnable now with
 * {@code job.definition.run}. A failed run is logged and leaves the schedule running; the next run is the retry, and
 * a failure of one source never delays another's job.
 */
public final class DirectoryJob extends SelfSchedulingLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DirectoryJob.class);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);


    private final DirectorySync sync;
    private final boolean sweep;
    private final RunLock lock;
    private final Duration lease;

    private DirectoryJob(DirectorySync sync, boolean sweep, TaskScheduler scheduler, ScheduleSpec schedule,
                         RunLock lock, Duration lease) {
        super(name(sync.source(), sweep), scheduler, schedule, DRAIN_TIMEOUT);
        this.sync = sync;
        this.sweep = sweep;
        this.lock = lock;
        this.lease = lease;
    }

    /**
     * The incremental sync job.
     *
     * @param sync         the source's sync
     * @param scheduler    the scheduler it runs on
     * @param initialDelay {@code initial-delay}, before the first run
     * @param interval     {@code sync-interval}
     * @param lock      the run lock
     * @param lease     the run lease
     * @return the job
     */
    public static DirectoryJob sync(DirectorySync sync, TaskScheduler scheduler, Duration initialDelay,
                                    Duration interval, RunLock lock, Duration lease) {
        return new DirectoryJob(sync, false, scheduler, ScheduleSpec.fixedDelay(interval, initialDelay), lock, lease);
    }

    /**
     * The sweep job, first run one interval after start: a source with no watermark is swept by its first sync.
     *
     * @param sync      the source's sync
     * @param scheduler the scheduler it runs on
     * @param interval  {@code sweep-interval}
     * @param lock      the run lock
     * @param lease     the run lease
     * @return the job
     */
    public static DirectoryJob sweep(DirectorySync sync, TaskScheduler scheduler, Duration interval, RunLock lock,
                                     Duration lease) {
        return new DirectoryJob(sync, true, scheduler, ScheduleSpec.fixedDelay(interval, interval), lock, lease);
    }

    /**
     * A source's job name, which is also its run lock's.
     *
     * @param source the source
     * @param sweep  the sweep job rather than the sync
     * @return {@code ludwig-identity-<source>-sync} or {@code -sweep}
     */
    public static String name(String source, boolean sweep) {
        return "ludwig-identity-" + source + (sweep ? "-sweep" : "-sync");
    }

    @Override
    protected void runOnce() {
        lock.tryAcquire(jobName(), lease).ifPresent(this::runUnder);
    }

    private void runUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            if (sweep) {
                sync.sweep();
            } else {
                sync.sync();
            }
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - a failed run must not end the schedule; the next one retries, and the
            // failure is already recorded as the source's outcome.
            log.warn("{} failed: {}", jobName(), e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}
