package ru.ludwigandreas.db.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

public class MicrometerDbCoreMetrics implements DbCoreMetrics {

    private static final String PREFIX = "ludwig.db.";

    private final MeterRegistry registry;

    public MicrometerDbCoreMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordAuditorResolved(boolean present) {
        registry.counter(PREFIX + "auditor.resolved", Tags.of("present", String.valueOf(present))).increment();
    }

    @Override
    public void recordSnapshotMutationBlocked(String entityType) {
        registry.counter(PREFIX + "snapshot.mutation.blocked", Tags.of("entityType", entityType)).increment();
    }
}
