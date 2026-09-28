package ru.ludwigandreas.cache.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheSettings;

/**
 * Records nothing.
 *
 * <p>Registered when there is no {@code MeterRegistry} on the context, and useful in a test that is
 * asserting on cache behaviour rather than on instrumentation. Caffeine's own statistics are still
 * collected - {@code recordStats()} is not conditional on this - so switching to a real registry later
 * shows the hit ratio from that point without a restart being a behaviour change.
 */
public class NoopCacheMetrics implements CacheMetrics {

    @Override
    public void registerCache(CacheSettings settings, Cache<String, ?> local) {
        // nothing to bind
    }

    @Override
    public void recordHit(String cache, String tier) {
        // nothing to count
    }

    @Override
    public void recordMiss(String cache) {
        // nothing to count
    }

    @Override
    public void recordNegativeHit(String cache) {
        // nothing to count
    }

    @Override
    public void recordLoad(String cache, String outcome, Duration took) {
        // nothing to count
    }

    @Override
    public void recordEarlyRefresh(String cache) {
        // nothing to count
    }

    @Override
    public void recordLeaseLost(String cache, boolean servedStale) {
        // nothing to count
    }

    @Override
    public void recordSharedEvictionFailure(String cache) {
        // nothing to count
    }

    @Override
    public void recordSharedError(String cache, String operation) {
        // nothing to count
    }

    @Override
    public void recordSharedShapeMismatch(String cache) {
        // nothing to count
    }

    @Override
    public void recordSharedTierAvailability(String cache, boolean available) {
        // nothing to gauge
    }
}
