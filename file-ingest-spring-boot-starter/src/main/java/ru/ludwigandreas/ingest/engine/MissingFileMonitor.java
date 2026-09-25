package ru.ludwigandreas.ingest.engine;

import com.querydsl.core.types.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.ingest.audit.IngestAuditEvent;
import ru.ludwigandreas.ingest.audit.IngestAuditLogger;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.entity.QFileIngestRun;
import ru.ludwigandreas.ingest.metrics.IngestMetrics;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.ingest.repository.IngestRunQueries;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Reports the file that never came.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Every other signal this module produces is about a run that happened. For a once-a-day job, the
 * failure nobody notices is the one where nothing happens: the schedule fires at half past six, the
 * listing is empty, a line is written at DEBUG, and the pass exits successfully. The run count stays
 * flat because there was no run; the error count stays at zero because there was no error; and the
 * table the ingest feeds quietly goes stale for as long as anyone lets it, which in practice is until
 * somebody downstream asks why a number looks wrong.
 *
 * <p>So the absence has to be a signal of its own, and it has to be pushed rather than inferred. That
 * is the same reasoning as the run-lock lease - an absence nothing reports is an absence nobody sees -
 * and the two belong next to each other in the README.
 *
 * <h2>It checks for a COMPLETED run, not for any run</h2>
 *
 * <p>A run that started and failed is not a file that arrived. Counting it would silence the alarm on
 * exactly the morning it should ring, because a partner sending a corrupt file and a partner sending
 * nothing are both mornings where the data did not land.
 *
 * <h2>Every instance runs it, and that is fine</h2>
 *
 * <p>No lock: the check is one indexed query per task per minute and it writes nothing. Putting it
 * under a lock would mean that when the instance holding the lock dies, the one signal about things
 * not happening also stops happening - which is precisely the wrong failure mode for this particular
 * component.
 */
@Slf4j
public class MissingFileMonitor extends SelfSchedulingLifecycle {

    /** The job's name, as it appears in its log lines. */
    public static final String JOB_NAME = "ludwig-file-ingest-missing-monitor";

    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final IngestTaskRegistry registry;
    private final FileIngestRunRepository runs;
    private final IngestMetrics metrics;
    private final IngestAuditLogger audit;
    private final Clock clock;

    /**
     * Creates the monitor.
     *
     * @param registry      the configured tasks
     * @param runs          the run table
     * @param metrics       where the gauge goes
     * @param audit         where the first observation of an absence is recorded
     * @param taskScheduler the scheduler to register with
     * @param drainTimeout  how long shutdown waits
     * @param clock         the clock
     */
    public MissingFileMonitor(IngestTaskRegistry registry, FileIngestRunRepository runs,
                              IngestMetrics metrics, IngestAuditLogger audit,
                              TaskScheduler taskScheduler, Duration drainTimeout, Clock clock) {
        super(JOB_NAME, taskScheduler, ScheduleSpec.fixedDelay(INTERVAL, INTERVAL), drainTimeout);
        this.registry = registry;
        this.runs = runs;
        this.metrics = metrics;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        registry.all().forEach(this::check);
    }

    private void check(RegisteredIngestTask task) {
        FileIngestProperties.Alert alert = task.settings().getAlert();
        LocalTime expectedBy = alert.getExpectedBy();
        if (expectedBy == null || !task.settings().isEnabled()) {
            metrics.recordMissing(task.name(), 0);
            return;
        }
        ZoneId zone = ZoneId.of(alert.getZone());
        ZonedDateTime now = clock.instant().atZone(zone);
        ZonedDateTime deadline = now.toLocalDate().atTime(expectedBy).atZone(zone);
        if (now.isBefore(deadline)) {
            // Before the deadline, an absent file is not yet news. Reporting zero rather than leaving
            // the gauge at its last value matters: a gauge that keeps yesterday's 1 until the next
            // file arrives would alert every morning between midnight and the deadline.
            metrics.recordMissing(task.name(), 0);
            return;
        }
        boolean arrived = completedSince(task.name(), now.toLocalDate(), zone);
        metrics.recordMissing(task.name(), arrived ? 0 : 1);
        if (!arrived) {
            log.warn("Ingest task {} has had no completed run today; expected by {} {}",
                    task.name(), expectedBy, alert.getZone());
            audit.record(new IngestAuditEvent(clock.instant(), null, task.name(),
                    IngestAuditEvent.MISSING, Map.of("expectedBy", expectedBy.toString(),
                    "zone", alert.getZone())));
        }
    }

    private boolean completedSince(String task, LocalDate day, ZoneId zone) {
        Predicate today = IngestRunQueries.completedFor(task)
                .and(QFileIngestRun.fileIngestRun.finishedAt.goe(day.atStartOfDay(zone).toInstant()));
        return runs.exists(today);
    }
}
