package ru.ludwigandreas.pat.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * Micrometer implementation.
 *
 * <p>Meter names are prefixed {@code ludwig.pat.} so a dashboard can select the whole subsystem, and the
 * reason tag on a failure is low-cardinality by construction - it comes from a fixed set of causes rather
 * than from anything a caller supplies. A tag fed from request data is how a metrics backend acquires a
 * million time series from one endpoint.
 */
@RequiredArgsConstructor
public class MicrometerPatMetrics implements PatMetrics {

    private final MeterRegistry registry;

    @Override
    public void recordExchange(String audience) {
        registry.counter("ludwig.pat.exchange", "audience", audience).increment();
    }

    @Override
    public void recordExchangeFailure(String reason) {
        registry.counter("ludwig.pat.exchange.failure", "reason", reason).increment();
    }

    @Override
    public void recordRateLimited(String limit) {
        registry.counter("ludwig.pat.rate-limited", "limit", limit).increment();
    }

    @Override
    public void recordLastUsedWriteFailed() {
        registry.counter("ludwig.pat.last-used.write-failed").increment();
    }
}
