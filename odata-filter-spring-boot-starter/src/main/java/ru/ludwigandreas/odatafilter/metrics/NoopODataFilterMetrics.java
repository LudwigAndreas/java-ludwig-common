package ru.ludwigandreas.odatafilter.metrics;

import java.time.Duration;

public class NoopODataFilterMetrics implements ODataFilterMetrics {

    @Override
    public void recordFilterApplied(String entityType) {
        // no-op
    }

    @Override
    public void recordFilterRejected(String entityType, String reason) {
        // no-op
    }

    @Override
    public void recordParseDuration(String entityType, Duration duration) {
        // no-op
    }
}
