package ru.ludwigandreas.identity.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ru.ludwigandreas.identity.feed.ApplyOutcome;

/**
 * {@code ludwig_identity_changes_total{source,type,outcome}}, {@code ludwig_identity_sweeps_total{source,outcome}} and
 * {@code ludwig_identity_sweep_disabled_total{source}}. Sources are declared in configuration, so the tag values are
 * bounded.
 */
public final class MicrometerIdentityMetrics implements IdentityMetrics {

    private final MeterRegistry registry;

    public MicrometerIdentityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void change(String source, String type, ApplyOutcome outcome) {
        Counter.builder("ludwig.identity.changes")
                .description("Identity changes received, by what the projection did with them")
                .tag("source", source).tag("type", type).tag("outcome", outcome.tag())
                .register(registry).increment();
    }

    @Override
    public void sweep(String source, boolean aborted, long disabled) {
        Counter.builder("ludwig.identity.sweeps")
                .description("Complete listings of an identity source, by outcome")
                .tag("source", source).tag("outcome", aborted ? "aborted" : "completed")
                .register(registry).increment();
        Counter.builder("ludwig.identity.sweep.disabled")
                .description("Users disabled because a complete listing of their source no longer contained them")
                .tag("source", source)
                .register(registry).increment(disabled);
    }
}
