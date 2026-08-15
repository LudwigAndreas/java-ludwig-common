package ru.ludwigandreas.outbox.metrics;

import java.time.Duration;

/**
 * Instrumentation hook for the publish/dispatch lifecycle. {@link NoopOutboxMetrics} is always
 * registered as a fallback; {@link MicrometerOutboxMetrics} replaces it when both Micrometer is on the
 * classpath and {@code ludwig.outbox.metrics.enabled=true} (the default).
 */
public interface OutboxMetrics {

    void recordPublished(String eventType, String transport);

    void recordFiltered(String eventType);

    void recordDispatchSucceeded(String eventType, String transport, String destination);

    void recordDispatchFailed(String eventType, String transport, String destination);

    void recordDeadLettered(String eventType, String transport);

    void recordDispatchDuration(String transport, String destination, Duration duration);

    /**
     * End-to-end latency from {@code OutboxMessage.createdAt} to the moment it was recorded PUBLISHED -
     * time actually spent sitting in the outbox (polling interval, retry backoff, queueing behind other
     * messages), not just the dispatch call itself ({@link #recordDispatchDuration}). The metric worth
     * alerting an SLA on.
     */
    void recordEndToEndLatency(String eventType, String transport, Duration duration);
}
