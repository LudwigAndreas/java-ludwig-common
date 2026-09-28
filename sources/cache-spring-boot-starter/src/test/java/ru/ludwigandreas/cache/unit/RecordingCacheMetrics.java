package ru.ludwigandreas.cache.unit;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.metrics.CacheMetrics;

/**
 * Counts what the real metrics would publish.
 *
 * <p>Asserting on a Micrometer {@code SimpleMeterRegistry} would work too and would test meter names as well;
 * this is here because several of the behaviours worth pinning are only observable as a counter - "a failed
 * shared eviction is counted and does not throw" has no other outcome to assert on, which is exactly why it
 * needed a metric in the first place.
 */
class RecordingCacheMetrics implements CacheMetrics {

    private final List<String> events = new ArrayList<>();
    private final AtomicInteger sharedEvictionFailures = new AtomicInteger();
    private final AtomicInteger negativeHits = new AtomicInteger();
    private final AtomicInteger earlyRefreshes = new AtomicInteger();
    private final AtomicInteger shapeMismatches = new AtomicInteger();

    @Override
    public void registerCache(CacheSettings settings, Cache<String, ?> local) {
        events.add("register:" + settings.name());
    }

    @Override
    public void recordHit(String cache, String tier) {
        events.add("hit:" + cache + ":" + tier);
    }

    @Override
    public void recordMiss(String cache) {
        events.add("miss:" + cache);
    }

    @Override
    public void recordNegativeHit(String cache) {
        negativeHits.incrementAndGet();
        events.add("negative-hit:" + cache);
    }

    @Override
    public void recordLoad(String cache, String outcome, Duration took) {
        events.add("load:" + cache + ":" + outcome);
    }

    @Override
    public void recordEarlyRefresh(String cache) {
        earlyRefreshes.incrementAndGet();
        events.add("early-refresh:" + cache);
    }

    @Override
    public void recordLeaseLost(String cache, boolean servedStale) {
        events.add("lease-lost:" + cache + ":" + (servedStale ? "stale" : "loaded"));
    }

    @Override
    public void recordSharedEvictionFailure(String cache) {
        sharedEvictionFailures.incrementAndGet();
        events.add("shared-eviction-failure:" + cache);
    }

    @Override
    public void recordSharedError(String cache, String operation) {
        events.add("shared-error:" + cache + ":" + operation);
    }

    @Override
    public void recordSharedShapeMismatch(String cache) {
        shapeMismatches.incrementAndGet();
        events.add("shared-shape-mismatch:" + cache);
    }

    @Override
    public void recordSharedTierAvailability(String cache, boolean available) {
        events.add("shared-available:" + cache + ":" + available);
    }

    List<String> events() {
        return events;
    }

    int sharedEvictionFailures() {
        return sharedEvictionFailures.get();
    }

    int negativeHits() {
        return negativeHits.get();
    }

    int earlyRefreshes() {
        return earlyRefreshes.get();
    }

    int shapeMismatches() {
        return shapeMismatches.get();
    }
}
