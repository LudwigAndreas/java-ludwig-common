package ru.ludwigandreas.export.metrics;

/**
 * What the module reports when Micrometer is absent or instrumentation is switched off.
 *
 * <p>A no-op implementation rather than a null check at every call site: an engine littered with
 * {@code if (metrics != null)} grows one that somebody forgets, and the forgotten one is a
 * {@code NullPointerException} in the middle of a run that was otherwise about to succeed.
 */
public final class NoopExportMetrics implements ExportMetrics {

    /** The only instance there is any reason to have. */
    public static final NoopExportMetrics INSTANCE = new NoopExportMetrics();

    private NoopExportMetrics() {
    }

    @Override
    public void runFinished(String definitionKey, String outcome, long rowsWritten, long millis) {
        // Nothing is recorded; see the class comment.
    }

    @Override
    public void outputStored(String definitionKey, String formatId, long sizeBytes) {
        // Nothing is recorded.
    }

    @Override
    public void enrichmentCall(String definitionKey, String stageName, String outcome, long millis) {
        // Nothing is recorded.
    }

    @Override
    public void enrichmentCacheAccess(String definitionKey, String stageName, long hits, long misses) {
        // Nothing is recorded.
    }

    @Override
    public void enrichmentKeysMissing(String definitionKey, String stageName, long count) {
        // Nothing is recorded.
    }

    @Override
    public void stageDegraded(String definitionKey, String stageName) {
        // Nothing is recorded.
    }

    @Override
    public void rowsDropped(String definitionKey, String stageName, long count) {
        // Nothing is recorded.
    }

    @Override
    public void bytesReclaimed(long bytes) {
        // Nothing is recorded.
    }
}
