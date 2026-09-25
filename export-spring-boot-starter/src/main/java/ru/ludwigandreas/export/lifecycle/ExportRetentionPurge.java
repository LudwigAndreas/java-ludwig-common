package ru.ludwigandreas.export.lifecycle;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.entity.ExportReportOutput;
import ru.ludwigandreas.export.metrics.ExportMetrics;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Deletes report files whose retention has elapsed.
 *
 * <h2>Why retention is not optional</h2>
 *
 * <p>A report is a copy of production data in a portable file, and a store of them that never expires
 * is a growing, unindexed, unmonitored copy of the database - a materially worse disclosure risk than
 * the reports themselves, because nobody is watching it and nobody remembers it is there.
 *
 * <h2>The row outlives the bytes</h2>
 *
 * <p>The output row is kept and marked {@code purged_at} rather than deleted. The audit trail is
 * about who exported what, and deleting the record along with the file would make the trail shrink
 * exactly as far back as the retention window - which is the opposite of what a trail is for. The
 * rows themselves are removed by a separate, much longer retention on the run.
 *
 * <h2>One instance at a time</h2>
 *
 * <p>Under {@code job-core}'s leased {@code RunLock}, because every instance runs this scheduler and
 * they would otherwise all delete the same files: harmless in outcome - a delete of something absent
 * is a success - and wasteful in a way that scales with the estate. The lock is leased rather than
 * held, so an instance that dies mid-purge does not stop the next one for good.
 */
@Slf4j
public class ExportRetentionPurge extends SelfSchedulingLifecycle {

    /** The name this job's log lines carry. */
    public static final String JOB_NAME = "ludwig-export-retention-purge";

    /** How many outputs one pass removes, so a long backlog is worked through rather than loaded. */
    private static final int BATCH_SIZE = 200;

    private final ExportReportOutputRepository outputs;
    private final ReportSink sink;
    private final RunLock lock;
    private final ExportProperties properties;
    private final ExportMetrics metrics;
    private final Clock clock;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ExportRetentionPurge(TaskScheduler taskScheduler, ExportReportOutputRepository outputs,
                                ReportSink sink, RunLock lock, ExportProperties properties,
                                ExportMetrics metrics, Clock clock) {
        super(JOB_NAME, taskScheduler,
                new ScheduleSpec(properties.getSink().getPurgeInterval(), null,
                        properties.getSink().getPurgeInterval()),
                properties.getPoller().getDrainTimeout());
        this.outputs = outputs;
        this.sink = sink;
        this.lock = lock;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        lock.tryAcquire(JOB_NAME, properties.getSink().getPurgeInterval().multipliedBy(2))
                .ifPresent(this::purgeUnder);
    }

    private void purgeUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            long bytes = purgeBatch();
            if (bytes > 0) {
                log.info("Retention purge reclaimed {} bytes of report output", bytes);
            }
        } catch (Exception e) {
            // The handle's close is the only thing that can throw here that runOnce has not already
            // let out; failing to release a leased lock is not worth failing the job over, because
            // the lease expires on its own.
            log.warn("Could not release the retention purge lock: {}", e.toString());
        }
    }

    /**
     * Removes one batch, deleting from the sink before marking the row.
     *
     * <p>That order on purpose. A row marked purged whose file is still there is a leaked file nobody
     * will ever look for again; a file deleted whose row is not yet marked is retried next pass, and
     * deleting something absent is defined as a success. Of the two ways to be interrupted halfway,
     * only one of them leaks.
     */
    @Transactional
    protected long purgeBatch() {
        List<ExportReportOutput> expired = outputs.findByPurgedAtIsNullAndExpiresAtBefore(
                clock.instant(), PageRequest.of(0, BATCH_SIZE));
        long reclaimed = 0;
        for (ExportReportOutput output : expired) {
            try {
                sink.delete(output.getSinkUri());
            } catch (IOException e) {
                // Left unmarked so the next pass tries again. A sink that is down for an hour should
                // not cost the estate a set of files nothing will ever revisit.
                log.warn("Could not delete expired report output {}: {}", output.getSinkUri(),
                        e.toString());
                continue;
            }
            output.setPurgedAt(clock.instant());
            outputs.save(output);
            reclaimed += output.getSizeBytes();
        }
        metrics.bytesReclaimed(reclaimed);
        return reclaimed;
    }
}
