package ru.ludwigandreas.export.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * The module's meters, in Micrometer.
 *
 * <p>Meter names follow the platform's {@code ludwig.<module>.<subject>} shape so that a dashboard
 * built for one module reads the same as a dashboard built for another. The four subjects are the
 * four questions an operator asks about reporting: are runs finishing, how big are the files, are
 * the partners answering, and is the cache doing its job.
 *
 * <p>The cache meters are the ones worth having on a dashboard from day one. At the design point the
 * difference between a working cache and a broken one is roughly two orders of magnitude in partner
 * calls, and a cache sized below the batch size returns nothing while looking entirely healthy from
 * the outside - so hit rate is not an optimisation metric here, it is a correctness signal for the
 * configuration.
 */
public class MicrometerExportMetrics implements ExportMetrics {

    private static final String RUNS = "ludwig.export.runs";
    private static final String OUTPUT_BYTES = "ludwig.export.output.bytes";
    private static final String ENRICHMENT_CALLS = "ludwig.export.enrichment.calls";
    private static final String CACHE_HITS = "ludwig.export.enrichment.cache.hits";
    private static final String CACHE_MISSES = "ludwig.export.enrichment.cache.misses";
    private static final String KEYS_MISSING = "ludwig.export.enrichment.keys.missing";
    private static final String DEGRADED = "ludwig.export.enrichment.degraded";
    private static final String ROWS_DROPPED = "ludwig.export.rows.dropped";
    private static final String ROWS_WRITTEN = "ludwig.export.rows.written";
    private static final String BYTES_RECLAIMED = "ludwig.export.retention.reclaimed.bytes";

    private static final String TAG_DEFINITION = "definition";
    private static final String TAG_FORMAT = "format";
    private static final String TAG_STAGE = "stage";
    private static final String TAG_OUTCOME = "outcome";

    private final MeterRegistry registry;

    public MicrometerExportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void runFinished(String definitionKey, String outcome, long rowsWritten, long millis) {
        Timer.builder(RUNS)
                .tag(TAG_DEFINITION, definitionKey)
                .tag(TAG_OUTCOME, outcome)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
        counter(ROWS_WRITTEN, TAG_DEFINITION, definitionKey).increment(rowsWritten);
    }

    @Override
    public void outputStored(String definitionKey, String formatId, long sizeBytes) {
        Counter.builder(OUTPUT_BYTES)
                .tag(TAG_DEFINITION, definitionKey)
                .tag(TAG_FORMAT, formatId)
                .baseUnit("bytes")
                .register(registry)
                .increment(sizeBytes);
    }

    @Override
    public void enrichmentCall(String definitionKey, String stageName, String outcome, long millis) {
        Timer.builder(ENRICHMENT_CALLS)
                .tag(TAG_DEFINITION, definitionKey)
                .tag(TAG_STAGE, stageName)
                .tag(TAG_OUTCOME, outcome)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void enrichmentCacheAccess(String definitionKey, String stageName, long hits, long misses) {
        if (hits > 0) {
            stageCounter(CACHE_HITS, definitionKey, stageName).increment(hits);
        }
        if (misses > 0) {
            stageCounter(CACHE_MISSES, definitionKey, stageName).increment(misses);
        }
    }

    @Override
    public void enrichmentKeysMissing(String definitionKey, String stageName, long count) {
        if (count > 0) {
            stageCounter(KEYS_MISSING, definitionKey, stageName).increment(count);
        }
    }

    @Override
    public void stageDegraded(String definitionKey, String stageName) {
        stageCounter(DEGRADED, definitionKey, stageName).increment();
    }

    @Override
    public void rowsDropped(String definitionKey, String stageName, long count) {
        if (count > 0) {
            stageCounter(ROWS_DROPPED, definitionKey, stageName).increment(count);
        }
    }

    @Override
    public void bytesReclaimed(long bytes) {
        if (bytes > 0) {
            Counter.builder(BYTES_RECLAIMED).baseUnit("bytes").register(registry).increment(bytes);
        }
    }

    private Counter stageCounter(String name, String definitionKey, String stageName) {
        return Counter.builder(name)
                .tag(TAG_DEFINITION, definitionKey)
                .tag(TAG_STAGE, stageName)
                .register(registry);
    }

    private Counter counter(String name, String tag, String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }
}
