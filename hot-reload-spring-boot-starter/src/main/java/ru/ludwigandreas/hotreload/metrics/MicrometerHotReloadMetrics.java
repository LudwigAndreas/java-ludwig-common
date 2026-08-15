package ru.ludwigandreas.hotreload.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MicrometerHotReloadMetrics implements HotReloadMetrics {

    private static final String PREFIX = "ludwig.hotreload.";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicLong> lastSuccessEpochMillis = new ConcurrentHashMap<>();

    public MicrometerHotReloadMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordReloadSucceeded(String sourceId, int changedKeyCount) {
        registry.counter(PREFIX + "reload.succeeded", Tags.of("source", sourceId)).increment();
        registry.summary(PREFIX + "reload.changed.keys", Tags.of("source", sourceId)).record(changedKeyCount);
        lastSuccessTimestamp(sourceId).set(System.currentTimeMillis());
    }

    @Override
    public void recordReloadFailed(String sourceId, String exceptionType) {
        registry.counter(PREFIX + "reload.failed", Tags.of("source", sourceId, "exception", exceptionType)).increment();
    }

    /** Lazily registers a "seconds since last successful reload" gauge the first time a source is seen. */
    private AtomicLong lastSuccessTimestamp(String sourceId) {
        return lastSuccessEpochMillis.computeIfAbsent(sourceId, id -> {
            AtomicLong lastSuccessAt = new AtomicLong(System.currentTimeMillis());
            Gauge.builder(PREFIX + "reload.seconds.since.last.success", lastSuccessAt,
                            value -> (System.currentTimeMillis() - value.get()) / 1000.0)
                    .tag("source", id)
                    .register(registry);
            return lastSuccessAt;
        });
    }
}
