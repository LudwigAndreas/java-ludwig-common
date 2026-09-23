package ru.ludwigandreas.reconciliation.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Micrometer implementation.
 *
 * <p>Gauges are registered once, at wiring time, against a supplier the engine owns - rather than
 * being set from inside the loop that computes them. A gauge that is only written while a run is in
 * progress reports a stale value between runs and, worse, keeps reporting the last healthy value of a
 * task that has stopped running altogether. That is exactly the failure the freshness-lag gauge
 * exists to catch, so it has to be pulled rather than pushed.
 */
public class MicrometerReconciliationMetrics implements ReconciliationMetrics {

    private static final String PREFIX = "reconciliation.";

    private final MeterRegistry registry;

    /**
     * Creates the metrics.
     *
     * @param registry the application's meter registry
     */
    public MicrometerReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordRun(String task, String tier, RunOutcome outcome, Duration duration) {
        registry.timer(PREFIX + "run", Tags.of("task", task, "tier", tier,
                "outcome", outcome.name().toLowerCase(Locale.ROOT))).record(duration);
    }

    @Override
    public void recordFetched(String task, int count) {
        registry.counter(PREFIX + "records.fetched", Tags.of("task", task)).increment(count);
    }

    @Override
    public void recordRecordOutcome(String task, String outcome) {
        registry.counter(PREFIX + "records.settled", Tags.of("task", task, "outcome", outcome)).increment();
    }

    @Override
    public void recordNotFound(String task, String policy) {
        registry.counter(PREFIX + "records.not_found", Tags.of("task", task, "policy", policy)).increment();
    }

    @Override
    public void recordFetchDuration(String task, String shape, Duration duration) {
        registry.timer(PREFIX + "fetch.duration", Tags.of("task", task, "shape", shape)).record(duration);
    }

    @Override
    public void recordApplyDuration(String task, Duration duration) {
        registry.timer(PREFIX + "apply.duration", Tags.of("task", task)).record(duration);
    }

    @Override
    public void registerFreshnessLag(String task, Supplier<Number> lagSeconds) {
        gauge(PREFIX + "freshness.lag", Tags.of("task", task), lagSeconds);
    }

    @Override
    public void registerStagedBacklog(String task, Supplier<Number> backlogDepth,
                                      Supplier<Number> oldestStagedAge) {
        gauge(PREFIX + "staged.depth", Tags.of("task", task), backlogDepth);
        gauge(PREFIX + "staged.oldest.age", Tags.of("task", task), oldestStagedAge);
    }

    @Override
    public void registerQuarantined(String task, Supplier<Number> count) {
        gauge(PREFIX + "records.quarantined", Tags.of("task", task), count);
    }

    @Override
    public void registerQuota(String quota, Supplier<Number> inFlight, Supplier<Number> limit,
                              Supplier<Number> oldestWaiterAge) {
        Tags tags = Tags.of("quota", quota);
        gauge(PREFIX + "quota.in_flight", tags, inFlight);
        gauge(PREFIX + "quota.limit", tags, limit);
        // Saturation is derived here rather than left to the dashboard so that every deployment reads
        // it the same way, including the division-by-zero case of a quota configured to zero slots.
        gauge(PREFIX + "quota.saturation", tags, () -> {
            double configured = limit.get().doubleValue();
            return configured <= 0 ? 1.0 : inFlight.get().doubleValue() / configured;
        });
        gauge(PREFIX + "quota.oldest_waiter.age", tags, oldestWaiterAge);
    }

    @Override
    public void recordQuotaAcquire(String quota, String task, boolean acquired, Duration waited) {
        registry.timer(PREFIX + "quota.acquire.wait",
                Tags.of("quota", quota, "task", task, "acquired", String.valueOf(acquired))).record(waited);
    }

    @Override
    public void recordLeaseReclaimed(String quota, String policy) {
        registry.counter(PREFIX + "quota.lease.reclaimed", Tags.of("quota", quota, "policy", policy)).increment();
    }

    @Override
    public void recordJobSettled(String task, String state, Duration duration, int polls) {
        Tags tags = Tags.of("task", task, "state", state);
        registry.timer(PREFIX + "job.duration", tags).record(duration);
        registry.summary(PREFIX + "job.polls", tags).record(polls);
        registry.counter(PREFIX + "job.settled", tags).increment();
    }

    @Override
    public void recordAmbiguousSubmit(String task, String resolution) {
        registry.counter(PREFIX + "job.ambiguous_submit",
                Tags.of("task", task, "resolution", resolution)).increment();
    }

    private void gauge(String name, Tags tags, Supplier<Number> value) {
        Gauge.builder(name, value, supplier -> supplier.get().doubleValue())
                .tags(tags)
                .strongReference(true)
                .register(registry);
    }
}
