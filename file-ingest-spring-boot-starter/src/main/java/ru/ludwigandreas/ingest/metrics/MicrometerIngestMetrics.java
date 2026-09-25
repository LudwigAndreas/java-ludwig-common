package ru.ludwigandreas.ingest.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ru.ludwigandreas.ingest.api.IngestRunStatus;

/**
 * The Micrometer binding.
 *
 * <p>Counters and timers are resolved through the registry on every call, which is what the registry
 * is built for; the one exception is the missing-file gauge, which has to hold a reference to the
 * value it reports and is therefore cached per task.
 */
public class MicrometerIngestMetrics implements IngestMetrics {

    private static final String RUNS = "ludwig.ingest.runs";
    private static final String SKIPPED = "ludwig.ingest.skipped";
    private static final String BYTES = "ludwig.ingest.bytes.read";
    private static final String RECORDS = "ludwig.ingest.records";
    private static final String BATCH_FLUSH = "ludwig.ingest.batch.flush";
    private static final String MERGE = "ludwig.ingest.merge";

    /**
     * The gauge worth alerting on - see {@link IngestMetrics} for why the absence of a file is the
     * failure a once-a-day job actually suffers.
     */
    private static final String MISSING = "ludwig.ingest.missing";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> missingByTask = new ConcurrentHashMap<>();

    /**
     * Creates the binding.
     *
     * @param registry the meter registry
     */
    public MicrometerIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordRun(String task, IngestRunStatus status, Duration duration) {
        Timer.builder(RUNS)
                .tag("task", task)
                .tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .record(duration);
    }

    @Override
    public void recordSkippedAlreadyProcessed(String task) {
        registry.counter(SKIPPED, "task", task, "reason", "already-processed").increment();
    }

    @Override
    public void recordBytesRead(String task, long bytes) {
        registry.counter(BYTES, "task", task).increment(bytes);
    }

    @Override
    public void recordRecords(String task, long read, long applied, long quarantined, long skipped) {
        // One counter with an outcome tag rather than four counters, so a dashboard can show the
        // breakdown and the total without summing four series that could each be renamed separately.
        registry.counter(RECORDS, "task", task, "outcome", "read").increment(read);
        registry.counter(RECORDS, "task", task, "outcome", "applied").increment(applied);
        registry.counter(RECORDS, "task", task, "outcome", "quarantined").increment(quarantined);
        registry.counter(RECORDS, "task", task, "outcome", "skipped").increment(skipped);
    }

    @Override
    public void recordBatchFlush(String task, Duration duration) {
        registry.timer(BATCH_FLUSH, "task", task).record(duration);
    }

    @Override
    public void recordMerge(String task, Duration duration) {
        registry.timer(MERGE, "task", task).record(duration);
    }

    @Override
    public void recordMissing(String task, int missing) {
        missingByTask.computeIfAbsent(task, name -> {
            AtomicInteger holder = new AtomicInteger();
            registry.gauge(MISSING, io.micrometer.core.instrument.Tags.of("task", name), holder,
                    AtomicInteger::doubleValue);
            return holder;
        }).set(missing);
    }
}
