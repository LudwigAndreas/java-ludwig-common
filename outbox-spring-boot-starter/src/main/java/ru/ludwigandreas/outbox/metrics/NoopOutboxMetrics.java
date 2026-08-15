package ru.ludwigandreas.outbox.metrics;

import java.time.Duration;

public class NoopOutboxMetrics implements OutboxMetrics {

    @Override
    public void recordPublished(String eventType, String transport) {
    }

    @Override
    public void recordFiltered(String eventType) {
    }

    @Override
    public void recordDispatchSucceeded(String eventType, String transport, String destination) {
    }

    @Override
    public void recordDispatchFailed(String eventType, String transport, String destination) {
    }

    @Override
    public void recordDeadLettered(String eventType, String transport) {
    }

    @Override
    public void recordDispatchDuration(String transport, String destination, Duration duration) {
    }
}
