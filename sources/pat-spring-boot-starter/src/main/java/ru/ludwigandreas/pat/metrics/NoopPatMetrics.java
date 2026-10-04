package ru.ludwigandreas.pat.metrics;

/** No-op fallback, so no call site needs a null check on the metrics bean. */
public class NoopPatMetrics implements PatMetrics {

    @Override
    public void recordExchange(String audience) {
        // no-op
    }

    @Override
    public void recordExchangeFailure(String reason) {
        // no-op
    }

    @Override
    public void recordRateLimited(String limit) {
        // no-op
    }

    @Override
    public void recordLastUsedWriteFailed() {
        // no-op
    }
}
