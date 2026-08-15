package ru.ludwigandreas.odatafilter.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;

public class MicrometerODataFilterMetrics implements ODataFilterMetrics {

    private static final String PREFIX = "odata.filter.";

    private final MeterRegistry registry;

    public MicrometerODataFilterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordFilterApplied(String entityType) {
        registry.counter(PREFIX + "applied", Tags.of("entityType", entityType)).increment();
    }

    @Override
    public void recordFilterRejected(String entityType, String reason) {
        registry.counter(PREFIX + "rejected", Tags.of("entityType", entityType, "reason", reason)).increment();
    }

    @Override
    public void recordParseDuration(String entityType, Duration duration) {
        registry.timer(PREFIX + "parse.duration", Tags.of("entityType", entityType)).record(duration);
    }
}
