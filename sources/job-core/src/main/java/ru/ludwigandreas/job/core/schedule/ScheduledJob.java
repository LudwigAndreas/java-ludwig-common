package ru.ludwigandreas.job.core.schedule;

import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;

/**
 * A {@link SelfSchedulingLifecycle} whose work is a plain {@link Runnable}, for the common case where
 * the job body already lives in a service bean and subclassing would add a type that carries no
 * behaviour of its own.
 */
public class ScheduledJob extends SelfSchedulingLifecycle {

    private final Runnable work;

    /**
     * Creates a scheduled job.
     *
     * @param jobName       name used in this job's log lines
     * @param taskScheduler scheduler the job registers itself with
     * @param schedule      when to run
     * @param drainTimeout  how long shutdown waits for an in-flight run
     * @param work          the job body; exceptions are caught and logged by the base class
     */
    public ScheduledJob(String jobName,
                        TaskScheduler taskScheduler,
                        ScheduleSpec schedule,
                        Duration drainTimeout,
                        Runnable work) {
        super(jobName, taskScheduler, schedule, drainTimeout);
        this.work = work;
    }

    @Override
    protected void runOnce() {
        work.run();
    }
}
