package ru.ludwigandreas.reconciliation.metrics;

import java.time.Duration;
import java.util.function.Supplier;

/** Registered whenever Micrometer is absent or metrics are switched off; keeps callers branch-free. */
public class NoopReconciliationMetrics implements ReconciliationMetrics {

    @Override
    public void recordRun(String task, String tier, RunOutcome outcome, Duration duration) {
        // No-op.
    }

    @Override
    public void recordFetched(String task, int count) {
        // No-op.
    }

    @Override
    public void recordRecordOutcome(String task, String outcome) {
        // No-op.
    }

    @Override
    public void recordNotFound(String task, String policy) {
        // No-op.
    }

    @Override
    public void recordFetchDuration(String task, String shape, Duration duration) {
        // No-op.
    }

    @Override
    public void recordApplyDuration(String task, Duration duration) {
        // No-op.
    }

    @Override
    public void registerFreshnessLag(String task, Supplier<Number> lagSeconds) {
        // No-op.
    }

    @Override
    public void registerStagedBacklog(String task, Supplier<Number> backlogDepth,
                                      Supplier<Number> oldestStagedAge) {
        // No-op.
    }

    @Override
    public void registerQuarantined(String task, Supplier<Number> count) {
        // No-op.
    }

    @Override
    public void registerQuota(String quota, Supplier<Number> inFlight, Supplier<Number> limit,
                              Supplier<Number> oldestWaiterAge) {
        // No-op.
    }

    @Override
    public void recordQuotaAcquire(String quota, String task, boolean acquired, Duration waited) {
        // No-op.
    }

    @Override
    public void recordLeaseReclaimed(String quota, String policy) {
        // No-op.
    }

    @Override
    public void recordJobSettled(String task, String state, Duration duration, int polls) {
        // No-op.
    }

    @Override
    public void recordAmbiguousSubmit(String task, String resolution) {
        // No-op.
    }
}
