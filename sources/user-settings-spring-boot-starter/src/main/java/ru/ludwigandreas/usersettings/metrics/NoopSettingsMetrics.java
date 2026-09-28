package ru.ludwigandreas.usersettings.metrics;

import java.time.Duration;
import ru.ludwigandreas.usersettings.api.SettingLayer;

/**
 * Does nothing, and is what runs when Micrometer is absent or metrics are switched off.
 *
 * <p>A no-op implementation rather than null checks at every call site: the resolution path calls
 * these methods on every lookup, and a module whose hot path is littered with
 * {@code if (metrics != null)} eventually grows one that is missing.
 */
public class NoopSettingsMetrics implements SettingsMetrics {

    @Override
    public void recordCacheHit() {
        // nothing to record
    }

    @Override
    public void recordCacheMiss() {
        // nothing to record
    }

    @Override
    public void recordResolution(Duration duration, int settingCount) {
        // nothing to record
    }

    @Override
    public void recordWrite(String category, SettingLayer layer) {
        // nothing to record
    }

    @Override
    public void recordReset(String category, SettingLayer layer) {
        // nothing to record
    }

    @Override
    public void recordConsentDecision(String consentKey, String decision) {
        // nothing to record
    }

    @Override
    public void recordAdminRead() {
        // nothing to record
    }

    @Override
    public void recordStaleEventDropped() {
        // nothing to record
    }

    @Override
    public void recordUnreadableValue(SettingLayer layer) {
        // nothing to record
    }

    @Override
    public void recordBackfillPublished(String kind, long rowCount) {
        // nothing to record
    }
}
