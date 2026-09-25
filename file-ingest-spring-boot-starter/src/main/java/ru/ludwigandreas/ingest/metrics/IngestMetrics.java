package ru.ludwigandreas.ingest.metrics;

import java.time.Duration;
import ru.ludwigandreas.ingest.api.IngestRunStatus;

/**
 * Instrumentation for everything this module does.
 *
 * <p>{@code NoopIngestMetrics} is always registered, so nothing in the engine has to null-check;
 * {@code MicrometerIngestMetrics} replaces it when Micrometer is present and
 * {@code ludwig.ingest.metrics.enabled} is true. The same arrangement {@code OutboxMetrics},
 * {@code ExportMetrics} and {@code ReconciliationMetrics} use.
 *
 * <h2>The signal worth alerting on is the one about a file that did not arrive</h2>
 *
 * <p>{@code ludwig.ingest.missing} is non-zero when no file has been ingested for a task by its
 * configured {@code alert.expected-by} time. Every other number here can look perfectly healthy while
 * the thing the module exists to do is not happening: for a once-a-day job the failure nobody notices
 * is the file that never showed up. The schedule fires, finds nothing, logs at DEBUG and exits
 * successfully; the run count stays flat, the error count stays at zero, and the table quietly goes
 * stale for as long as anyone lets it.
 *
 * <p>This is the same reasoning as the run-lock lease - an absence that nothing reports is an absence
 * nobody sees - and it belongs next to it in the README.
 */
public interface IngestMetrics {

    /**
     * Records a completed or failed run.
     *
     * @param task     the task name
     * @param status   how it ended
     * @param duration how long it took
     */
    void recordRun(String task, IngestRunStatus status, Duration duration);

    /**
     * Records a run that found an object it had already ingested.
     *
     * <p>Its own counter rather than a run outcome, because it is not a run: nothing was read and
     * nothing was written. Counting it as a completed run would make the run rate look healthy on a
     * morning when the partner re-sent yesterday's file and today's never came.
     *
     * @param task the task name
     */
    void recordSkippedAlreadyProcessed(String task);

    /**
     * Records uncompressed bytes consumed.
     *
     * @param task  the task name
     * @param bytes how many
     */
    void recordBytesRead(String task, long bytes);

    /**
     * Records per-record outcomes for one batch.
     *
     * @param task        the task name
     * @param read        records read
     * @param applied     records written to staging
     * @param quarantined records set aside
     * @param skipped     records the applier collapsed
     */
    void recordRecords(String task, long read, long applied, long quarantined, long skipped);

    /**
     * Records how long one batch took to flush, checkpoint included.
     *
     * <p>Checkpoint included on purpose: the flush and the checkpoint are one transaction, so timing
     * them separately would report two numbers for one thing and invite somebody to try to make them
     * two things.
     *
     * @param task     the task name
     * @param duration how long it took
     */
    void recordBatchFlush(String task, Duration duration);

    /**
     * Records how long the final staging-to-target merge took.
     *
     * @param task     the task name
     * @param duration how long it took
     */
    void recordMerge(String task, Duration duration);

    /**
     * Reports how long it has been since a task last completed a run, for the missing-file gauge.
     *
     * <p>Pushed by the monitor rather than pulled by a gauge over a repository, because the query
     * behind it is a database read and a Micrometer gauge is evaluated on the scrape thread - a
     * scrape every fifteen seconds would put a query on the critical path of the metrics endpoint.
     *
     * @param task    the task name
     * @param missing {@code 1} when the expected-by time has passed with no completed run today,
     *                {@code 0} otherwise
     */
    void recordMissing(String task, int missing);
}
