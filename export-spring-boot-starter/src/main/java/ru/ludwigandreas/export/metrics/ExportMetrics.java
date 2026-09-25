package ru.ludwigandreas.export.metrics;

/**
 * What this module reports about itself.
 *
 * <h2>Tag cardinality is part of the contract</h2>
 *
 * <p>Every tag here is drawn from a set fixed at deploy time: a definition key, a format id, a stage
 * name, an outcome. None of them is a parameter value, a principal, a run id or a key - all of which
 * are unbounded, and any one of which would turn a metrics backend into a per-request time series
 * store and take the cardinality budget of the whole estate with it. A report is exactly the kind of
 * component where that mistake is tempting, because the interesting question is usually "who ran
 * what", and that question belongs to the audit trail rather than to the metrics.
 *
 * <p>Implementations must not throw. An outage in the metrics backend that stopped reports being
 * produced would be a strictly worse outage than the one it was reporting on.
 */
public interface ExportMetrics {

    /** A run finished, one way or another. */
    void runFinished(String definitionKey, String outcome, long rowsWritten, long millis);

    /** A file was stored. */
    void outputStored(String definitionKey, String formatId, long sizeBytes);

    /** One call to a partner, on behalf of one stage. */
    void enrichmentCall(String definitionKey, String stageName, String outcome, long millis);

    /** Keys a stage asked the cache for, and how many it already had. */
    void enrichmentCacheAccess(String definitionKey, String stageName, long hits, long misses);

    /** A stage's keys the partner did not know. */
    void enrichmentKeysMissing(String definitionKey, String stageName, long count);

    /** A stage failed and its policy was to degrade rather than to fail the run. */
    void stageDegraded(String definitionKey, String stageName);

    /** Rows dropped because a stage's missing policy was to drop them. */
    void rowsDropped(String definitionKey, String stageName, long count);

    /**
     * Bytes the retention purge reclaimed.
     *
     * <p>Untagged, deliberately. It answers an operational question about the volume a service is
     * holding, not an analytical one about which report is holding it - and tagging it by definition
     * would mean carrying a series per report for a number nobody breaks down that way.
     */
    void bytesReclaimed(long bytes);
}
