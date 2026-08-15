package ru.ludwigandreas.outbox.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;

public class MicrometerOutboxMetrics implements OutboxMetrics {

    private static final String PREFIX = "ludwig.outbox.";

    private final MeterRegistry registry;

    public MicrometerOutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordPublished(String eventType, String transport) {
        registry.counter(PREFIX + "published", Tags.of("eventType", eventType, "transport", transport)).increment();
    }

    @Override
    public void recordFiltered(String eventType) {
        registry.counter(PREFIX + "filtered", Tags.of("eventType", eventType)).increment();
    }

    @Override
    public void recordDispatchSucceeded(String eventType, String transport, String destination) {
        registry.counter(PREFIX + "dispatch.succeeded",
                Tags.of("eventType", eventType, "transport", transport, "destination", destination)).increment();
    }

    @Override
    public void recordDispatchFailed(String eventType, String transport, String destination) {
        registry.counter(PREFIX + "dispatch.failed",
                Tags.of("eventType", eventType, "transport", transport, "destination", destination)).increment();
    }

    @Override
    public void recordDeadLettered(String eventType, String transport) {
        registry.counter(PREFIX + "deadlettered", Tags.of("eventType", eventType, "transport", transport)).increment();
    }

    @Override
    public void recordDispatchDuration(String transport, String destination, Duration duration) {
        registry.timer(PREFIX + "dispatch.duration", Tags.of("transport", transport, "destination", destination))
                .record(duration);
    }
}
