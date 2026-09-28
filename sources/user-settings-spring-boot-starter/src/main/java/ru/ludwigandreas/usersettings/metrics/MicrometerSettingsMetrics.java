package ru.ludwigandreas.usersettings.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import ru.ludwigandreas.usersettings.api.SettingLayer;

/**
 * Micrometer implementation.
 *
 * <p>Every tag value here is a bounded, server-side vocabulary: a category declared in a
 * {@code SettingDefinition}, a {@link SettingLayer} constant, a fixed outcome string. Nothing
 * caller-controlled and nothing derived from a stored value is used as a tag - see the note on
 * {@link SettingsMetrics}.
 *
 * <p>The timer and the summary are resolved once in the constructor because they carry no variable
 * tags; the counters are resolved per call, which is a map lookup in Micrometer and keeps the tag
 * combinations from having to be enumerated up front.
 */
public class MicrometerSettingsMetrics implements SettingsMetrics {

    private final MeterRegistry registry;
    private final Timer resolutionTimer;
    private final DistributionSummary resolutionSize;

    /** Registers the two meters that carry no variable tags; the counters are resolved per call. */
    public MicrometerSettingsMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.resolutionTimer = Timer.builder("ludwig.user.settings.resolution")
                .description("Time to resolve a subject's complete settings set")
                .publishPercentileHistogram()
                .register(registry);
        this.resolutionSize = DistributionSummary.builder("ludwig.user.settings.resolution.size")
                .description("Number of settings produced by one resolution")
                .register(registry);
    }

    @Override
    public void recordCacheHit() {
        registry.counter("ludwig.user.settings.cache", "result", "hit").increment();
    }

    @Override
    public void recordCacheMiss() {
        registry.counter("ludwig.user.settings.cache", "result", "miss").increment();
    }

    @Override
    public void recordResolution(Duration duration, int settingCount) {
        resolutionTimer.record(duration.toNanos(), TimeUnit.NANOSECONDS);
        resolutionSize.record(settingCount);
    }

    @Override
    public void recordWrite(String category, SettingLayer layer) {
        registry.counter("ludwig.user.settings.write",
                "category", category, "layer", layer.name(), "action", "set").increment();
    }

    @Override
    public void recordReset(String category, SettingLayer layer) {
        registry.counter("ludwig.user.settings.write",
                "category", category, "layer", layer.name(), "action", "reset").increment();
    }

    @Override
    public void recordConsentDecision(String consentKey, String decision) {
        registry.counter("ludwig.user.settings.consent",
                "consent", consentKey, "decision", decision).increment();
    }

    @Override
    public void recordAdminRead() {
        registry.counter("ludwig.user.settings.admin.read").increment();
    }

    @Override
    public void recordStaleEventDropped() {
        registry.counter("ludwig.user.settings.projection.dropped", "reason", "stale").increment();
    }

    @Override
    public void recordUnreadableValue(SettingLayer layer) {
        registry.counter("ludwig.user.settings.value.unreadable", "layer", layer.name()).increment();
    }

    /**
     * Incremented by the whole run at once rather than per row, so a backfill of a million rows costs
     * one counter update instead of a million - and reads on a dashboard as the single operator action
     * it actually was.
     */
    @Override
    public void recordBackfillPublished(String kind, long rowCount) {
        registry.counter("ludwig.user.settings.backfill.published", "kind", kind).increment(rowCount);
    }
}
