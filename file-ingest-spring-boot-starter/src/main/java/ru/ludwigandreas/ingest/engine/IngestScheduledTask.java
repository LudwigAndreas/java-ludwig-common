package ru.ludwigandreas.ingest.engine;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * One task's schedule: a cron entry that wakes up and runs one pass.
 *
 * <h2>One lifecycle per task, not one for all of them</h2>
 *
 * <p>Tasks have different cron expressions - that is most of the reason they are separate tasks - and
 * a single scheduler firing at the union of them and then asking each task whether it was due would be
 * reimplementing cron badly. It would also couple the tasks: a pass that spent forty minutes on one
 * task's 2 GB file would delay every other task behind it, which for a set of daily ingests that all
 * expect to run between six and seven is the difference between finishing and not.
 *
 * <p>{@code SelfSchedulingLifecycle} already refuses to run twice at once, contains exceptions, and
 * drains on shutdown, so each of these gets that for free and none of it is written here. The drain is
 * why {@code ludwig.ingest.drain-timeout} exists: abandoning a run is safe because of the checkpoint,
 * but the work since the last one is thrown away, so it is worth waiting a little.
 */
@Slf4j
public class IngestScheduledTask extends SelfSchedulingLifecycle {

    private final String task;
    private final IngestPass pass;

    /**
     * Creates the schedule for one task.
     *
     * @param task          the task name
     * @param cron          its cron expression
     * @param taskScheduler the scheduler to register with
     * @param drainTimeout  how long shutdown waits for an in-flight pass
     * @param pass          what a firing does
     */
    public IngestScheduledTask(String task, String cron, TaskScheduler taskScheduler,
                               Duration drainTimeout, IngestPass pass) {
        super("ludwig-file-ingest-" + task, taskScheduler, ScheduleSpec.cron(cron), drainTimeout);
        this.task = task;
        this.pass = pass;
    }

    /**
     * The task this schedule belongs to.
     *
     * @return the task name
     */
    public String task() {
        return task;
    }

    @Override
    protected void runOnce() {
        // Exceptions are deliberately allowed out: SelfSchedulingLifecycle catches and logs them with
        // the job's name, and swallowing one here would cost exactly that log line - which is the only
        // thing that says which of several ingests failed.
        boolean ran = pass.runOnce(task);
        if (!ran) {
            log.debug("Ingest task {} is running on another instance", task);
        }
    }
}
