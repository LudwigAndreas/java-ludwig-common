package ru.ludwigandreas.usersettings.metrics;

import java.time.Duration;
import ru.ludwigandreas.usersettings.api.SettingLayer;

/**
 * Instrumentation hook for settings resolution and writes. {@link NoopSettingsMetrics} is the
 * always-available fallback; {@link MicrometerSettingsMetrics} replaces it when Micrometer is on the
 * classpath and {@code ludwig.user-settings.metrics.enabled=true} (the default).
 *
 * <h2>What must never become a tag</h2>
 *
 * <p><b>No setting value and no subject id, ever.</b> Both would be unbounded and caller-controlled,
 * which makes them a cardinality bomb in the metrics backend and a cost incident; a value would also
 * publish personal data into a store that is typically far less protected than the database it came
 * from, and that no erasure request will ever reach. Every tag below is a bounded, server-side
 * vocabulary - a category declared in code, a layer from a five-constant enum, an outcome. Per-subject
 * detail belongs in the audit trail, which is built to hold it and to be purged.
 *
 * <p>The signals worth alerting on: a falling cache hit ratio usually means eviction is firing more
 * than it should (a chatty producer, or a tenant-layer write on a hot path); a rising
 * {@code staleEventDropped} in a projection means the owner is republishing or the topic was
 * repartitioned; resolution latency rising without a traffic change means the scope set per subject
 * has grown - usually roles.
 */
public interface SettingsMetrics {

    void recordCacheHit();

    void recordCacheMiss();

    /**
     * How long one full resolution took, and how many settings it produced.
     *
     * <p>The count is recorded as its own summary rather than as a tag: it is a number that grows
     * with the registry, so tagging on it would create a new time series every time a service
     * declared another setting.
     */
    void recordResolution(Duration duration, int settingCount);

    /** A value was written. {@code category} comes from the definition, never from the value. */
    void recordWrite(String category, SettingLayer layer);

    /** A value was cleared, letting the layers below supply one again. */
    void recordReset(String category, SettingLayer layer);

    /** A consent decision was recorded. {@code consentKey} is a declared key, not a value. */
    void recordConsentDecision(String consentKey, String decision);

    /** An administrator read another subject's settings - the counterpart of the audit row. */
    void recordAdminRead();

    /**
     * A projected event was older than what is already stored and was dropped.
     *
     * <p>Normal in small numbers - it is what order tolerance looks like from the outside - and a
     * problem when it climbs, which is why it is a counter rather than a log line.
     */
    void recordStaleEventDropped();

    /** A stored value could not be decoded and the next layer down was used instead. */
    void recordUnreadableValue(SettingLayer layer);

    /**
     * Rows republished by a backfill run.
     *
     * <p>{@code kind} is {@code "setting"} or {@code "consent"} - two server-side constants, not
     * anything derived from a row. The count is the run's total rather than one increment per row,
     * because a backfill is an operator action measured in runs: what is worth seeing on a dashboard
     * is that one happened, when, and how big it was.
     *
     * <p>Expect the projection's {@code staleEventDropped} counter to spike alongside this. That is
     * the backfill working - a replica that was already current rejects everything it is sent - and
     * an alert on stale drops should be written to tolerate it.
     */
    void recordBackfillPublished(String kind, long rowCount);
}
