package ru.ludwigandreas.cache.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ru.ludwigandreas.cache.api.CacheSettings;

/**
 * Publishes every cache's behaviour to Micrometer.
 *
 * <h2>What Caffeine contributes, and what this adds</h2>
 *
 * <p>{@link CaffeineCacheMetrics} is bound per named cache, which is what finally makes hit ratio, load
 * time, eviction count and size observable - none of it existed before this module, because neither of the
 * two hand-written caches called {@code recordStats()}.
 *
 * <p>What Caffeine cannot see is everything this module added on top of it: which tier answered, whether a
 * hit was a cached absence, whether a load ended present, absent or failed, whether an entry was refreshed
 * early, whether a replica lost the load lease, and - the one to alert on - whether a shared eviction
 * failed. Those are counted here.
 *
 * <h2>Tags</h2>
 *
 * <p>Every meter carries {@code cache} and {@code purpose}. The purpose is on the meter rather than only in
 * configuration so that a dashboard can separate the two kinds of cache without a hard-coded list of names:
 * a security cache's hit ratio and a performance cache's hit ratio are not the same quantity and should not
 * share an axis.
 */
public class MicrometerCacheMetrics implements CacheMetrics {

    private static final String HITS = "ludwig.cache.hits";

    private static final String MISSES = "ludwig.cache.misses";

    private static final String NEGATIVE_HITS = "ludwig.cache.negative.hits";

    private static final String LOADS = "ludwig.cache.loads";

    private static final String EARLY_REFRESHES = "ludwig.cache.early.refreshes";

    private static final String LEASES_LOST = "ludwig.cache.lease.lost";

    private static final String SHARED_EVICTION_FAILURES = "ludwig.cache.shared.eviction.failures";

    private static final String SHARED_ERRORS = "ludwig.cache.shared.errors";

    private static final String SHARED_SHAPE_MISMATCHES = "ludwig.cache.shared.shape.mismatches";

    private static final String SHARED_AVAILABLE = "ludwig.cache.shared.available";

    private static final String MAXIMUM_SIZE = "ludwig.cache.maximum.size";

    private final MeterRegistry registry;

    /** Purpose per cache, so a counter recorded from a hot path does not have to carry the settings. */
    private final Map<String, String> purposes = new ConcurrentHashMap<>();

    /** The availability gauge's backing value, one per cache with a shared tier. */
    private final Map<String, AtomicInteger> availability = new ConcurrentHashMap<>();

    /** @param registry the service's registry */
    public MicrometerCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void registerCache(CacheSettings settings, Cache<String, ?> local) {
        purposes.put(settings.name(), settings.purpose().id());
        Tags tags = Tags.of("purpose", settings.purpose().id());
        CaffeineCacheMetrics.monitor(registry, local, settings.name(), tags);

        registry.gauge(MAXIMUM_SIZE, tags.and("cache", settings.name()), settings,
                resolved -> (double) resolved.maximumSize());
        if (settings.usesSharedTier()) {
            AtomicInteger available = availability.computeIfAbsent(
                    settings.name(), ignored -> new AtomicInteger(1));
            registry.gauge(SHARED_AVAILABLE, tags.and("cache", settings.name()), available,
                    AtomicInteger::doubleValue);
        }
    }

    @Override
    public void recordHit(String cache, String tier) {
        Counter.builder(HITS).tags(tagsFor(cache).and("tier", tier)).register(registry).increment();
    }

    @Override
    public void recordMiss(String cache) {
        Counter.builder(MISSES).tags(tagsFor(cache)).register(registry).increment();
    }

    @Override
    public void recordNegativeHit(String cache) {
        Counter.builder(NEGATIVE_HITS).tags(tagsFor(cache)).register(registry).increment();
    }

    @Override
    public void recordLoad(String cache, String outcome, Duration took) {
        Timer.builder(LOADS).tags(tagsFor(cache).and("outcome", outcome)).register(registry).record(took);
    }

    @Override
    public void recordEarlyRefresh(String cache) {
        Counter.builder(EARLY_REFRESHES).tags(tagsFor(cache)).register(registry).increment();
    }

    @Override
    public void recordLeaseLost(String cache, boolean servedStale) {
        Counter.builder(LEASES_LOST)
                .tags(tagsFor(cache).and("served", servedStale ? "stale" : "loaded"))
                .register(registry)
                .increment();
    }

    @Override
    public void recordSharedEvictionFailure(String cache) {
        Counter.builder(SHARED_EVICTION_FAILURES)
                .description("Shared entries that could not be deleted. Every replica now serves the stale"
                        + " value until the TTL expires; alert on this")
                .tags(tagsFor(cache))
                .register(registry)
                .increment();
    }

    @Override
    public void recordSharedError(String cache, String operation) {
        Counter.builder(SHARED_ERRORS)
                .tags(tagsFor(cache).and("operation", operation))
                .register(registry)
                .increment();
    }

    @Override
    public void recordSharedShapeMismatch(String cache) {
        Counter.builder(SHARED_SHAPE_MISMATCHES)
                .description("Shared entries discarded because their value shape is not this release's."
                        + " Non-zero during a rolling deploy means the key namespace version was not bumped")
                .tags(tagsFor(cache))
                .register(registry)
                .increment();
    }

    @Override
    public void recordSharedTierAvailability(String cache, boolean available) {
        AtomicInteger gauge = availability.get(cache);
        if (gauge != null) {
            gauge.set(available ? 1 : 0);
        }
    }

    private Tags tagsFor(String cache) {
        return Tags.of("cache", cache, "purpose", purposes.getOrDefault(cache, "unknown"));
    }
}
