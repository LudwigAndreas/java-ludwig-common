package ru.ludwigandreas.job.core.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Base class for a background job that schedules itself against an injected {@link TaskScheduler}.
 *
 * <h2>Why not {@code @Scheduled}</h2>
 *
 * <p>{@code @Scheduled} takes its interval as an annotation attribute, which means either a
 * placeholder string re-parsed from configuration that has already been bound to a {@link Duration},
 * or a SpEL expression - and, either way, it requires the <em>consuming application</em> to have
 * turned scheduling on with {@code @EnableScheduling}. A library starter that only works if the
 * service remembers to enable a feature of Spring is not plug-and-play, and the failure when it
 * forgets is silent: nothing runs, nothing logs, and the backlog grows. Scheduling against an
 * injected {@code TaskScheduler} the starter owns keeps the module self-contained and makes the
 * schedule a first-class configuration value.
 *
 * <h2>Non-reentrancy</h2>
 *
 * <p>A fixed-delay schedule already prevents overlap, but a cron schedule does not, and neither does
 * a manual trigger from an actuator endpoint arriving while a scheduled run is in progress. The lock
 * below makes a second entry a no-op that says so, rather than a second concurrent run of a job whose
 * claiming logic assumes one runner per instance.
 *
 * <h2>Draining on shutdown</h2>
 *
 * <p>{@link #stop()} cancels the schedule <em>without interrupting</em> a run that is already going,
 * then waits up to the configured drain timeout for it to finish. Interrupting instead would abandon
 * rows mid-claim: they stay marked in-progress, owned by a process that no longer exists, and are
 * only recovered when a stale reclaimer notices them minutes later. Waiting turns the common case -
 * a rolling deploy - into a clean handover.
 */
public abstract class SelfSchedulingLifecycle implements SmartLifecycle {

    /**
     * Phase placing these jobs late in startup and, correspondingly, early in shutdown: they must
     * stop before the data source and the web layer go away, because their last act is a database
     * write recording the outcome of whatever they were doing.
     */
    public static final int DEFAULT_PHASE = Integer.MAX_VALUE - 1024;

    /** How finely {@link #stop()} polls for the in-flight run to end while draining. */
    private static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(50);

    private static final Logger log = LoggerFactory.getLogger(SelfSchedulingLifecycle.class);

    private final String jobName;
    private final TaskScheduler taskScheduler;
    private final ScheduleSpec schedule;
    private final Duration drainTimeout;

    /**
     * Guards a single run. A plain lock rather than a boolean flag so that {@link #stop()} can observe
     * "a run is in progress" without racing against the run that is about to set it.
     */
    private final ReentrantLock runLock = new ReentrantLock();

    private volatile ScheduledFuture<?> scheduledFuture;
    private volatile boolean stopping;

    /**
     * Creates the job.
     *
     * @param jobName       name used in log lines and in the "already running" message; should match the
     *                      name the job is configured under, so an operator can grep for one string
     * @param taskScheduler scheduler the job registers itself with
     * @param schedule      when to run
     * @param drainTimeout  how long {@link #stop()} waits for an in-flight run before giving up on it
     */
    protected SelfSchedulingLifecycle(String jobName,
                                      TaskScheduler taskScheduler,
                                      ScheduleSpec schedule,
                                      Duration drainTimeout) {
        this.jobName = jobName;
        this.taskScheduler = taskScheduler;
        this.schedule = schedule;
        this.drainTimeout = drainTimeout;
    }

    /**
     * One execution of the job. Implementations should let exceptions propagate: {@link #tick()}
     * catches and logs them, and swallowing an exception in the subclass instead costs the log line
     * that says which job failed.
     */
    protected abstract void runOnce();

    /** The job's name, as used in its log lines. */
    public String jobName() {
        return jobName;
    }

    @Override
    public void start() {
        stopping = false;
        scheduledFuture = schedule.isCron()
                ? taskScheduler.schedule(this::tick, new CronTrigger(schedule.cron()))
                : taskScheduler.scheduleWithFixedDelay(
                        this::tick, Instant.now().plus(schedule.initialDelay()), schedule.fixedDelay());
        log.debug("Job '{}' scheduled ({})", jobName, schedule.isCron()
                ? "cron " + schedule.cron() : "every " + schedule.fixedDelay());
    }

    @Override
    public void stop() {
        stopping = true;
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null) {
            // false: never interrupt a run that is mid-claim - see the class comment.
            future.cancel(false);
        }
        scheduledFuture = null;
        drain();
    }

    @Override
    public boolean isRunning() {
        ScheduledFuture<?> future = scheduledFuture;
        return future != null && !future.isDone();
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }

    /**
     * Runs the job once, now, on the calling thread, unless a run is already in progress.
     *
     * <p>This is what an actuator "run now" operation calls. It shares the non-reentrancy guard with
     * the scheduled path, so triggering a run by hand while one is already going does nothing rather
     * than doubling the work.
     *
     * @return {@code true} if this call ran the job, {@code false} if a run was already in progress
     */
    public boolean runNow() {
        return tick();
    }

    /**
     * Executes one run under the non-reentrancy guard.
     *
     * @return whether this call actually ran the job
     */
    private boolean tick() {
        if (stopping || !runLock.tryLock()) {
            log.debug("Job '{}' skipped: {}", jobName, stopping ? "shutting down" : "previous run still in progress");
            return false;
        }
        try {
            runOnce();
            return true;
        } catch (Exception e) {
            // A scheduler tick is a framework boundary: an exception escaping here cancels the
            // schedule for the lifetime of the process, so everything is stopped and logged.
            log.error("Job '{}' run failed", jobName, e);
            return true;
        } finally {
            runLock.unlock();
        }
    }

    /** Waits for an in-flight run to finish, up to {@link #drainTimeout}. */
    private void drain() {
        if (drainTimeout.isZero() || drainTimeout.isNegative()) {
            return;
        }
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (runLock.isLocked() && System.nanoTime() < deadline) {
            try {
                if (runLock.tryLock(DRAIN_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
                    runLock.unlock();
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (runLock.isLocked()) {
            log.warn("Job '{}' did not finish within the {} drain timeout; its in-flight work will be "
                    + "recovered by the stale reclaimer on another instance", jobName, drainTimeout);
        }
    }
}
