package ru.ludwigandreas.ingest.metrics;

import java.time.Duration;
import ru.ludwigandreas.ingest.api.IngestRunStatus;

/**
 * Records nothing.
 *
 * <p>A real bean rather than a null, so the engine has no null checks to forget and no branch that is
 * only exercised in one of the two configurations. The cost of the indirection is a virtual call per
 * batch, against a batch that just wrote five thousand rows to a database.
 */
public class NoopIngestMetrics implements IngestMetrics {

    @Override
    public void recordRun(String task, IngestRunStatus status, Duration duration) {
        // Intentionally empty: see this class's documentation.
    }

    @Override
    public void recordSkippedAlreadyProcessed(String task) {
        // Intentionally empty.
    }

    @Override
    public void recordBytesRead(String task, long bytes) {
        // Intentionally empty.
    }

    @Override
    public void recordRecords(String task, long read, long applied, long quarantined, long skipped) {
        // Intentionally empty.
    }

    @Override
    public void recordBatchFlush(String task, Duration duration) {
        // Intentionally empty.
    }

    @Override
    public void recordMerge(String task, Duration duration) {
        // Intentionally empty.
    }

    @Override
    public void recordMissing(String task, int missing) {
        // Intentionally empty.
    }
}
