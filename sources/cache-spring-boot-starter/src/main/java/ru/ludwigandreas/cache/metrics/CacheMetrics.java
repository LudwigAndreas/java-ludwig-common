package ru.ludwigandreas.cache.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheSettings;

/**
 * Everything this module reports about a cache.
 *
 * <p>An SPI with a Micrometer implementation and a no-op, following the shape
 * {@code security-spring-boot-starter} and {@code user-settings-spring-boot-starter} already use, so a
 * service without a {@code MeterRegistry} still starts and a service that wants to assert on cache
 * behaviour in a test can substitute a recording double.
 *
 * <h2>Why this is not optional</h2>
 *
 * <p>Neither of the two caches this module replaces called Caffeine's {@code recordStats()}. The
 * consequence is worth stating plainly: <b>hit ratio was unobservable across the entire platform</b>. The
 * one number that says whether a cache is worth its TTL - whether raising the TTL would help, whether
 * the cache is doing anything at all - did not exist, for either of the two caches every request went
 * through. Statistics are on by default here and that is not configurable per cache; Caffeine's
 * recording is a handful of counters on a hit path that is already doing a hash lookup.
 *
 * <h2>The ones that matter, and why</h2>
 *
 * <ul>
 *   <li><b>hit ratio</b>, per tier - a shared hit and a local hit cost two different amounts;</li>
 *   <li><b>load time</b> - what a miss actually costs, which is the number that decides whether the
 *       shared tier or the load lease is worth it;</li>
 *   <li><b>size against the ceiling</b> - a cache permanently at {@code maximum-size} is undersized, and
 *       eviction pressure says so earlier and more clearly than the hit ratio does;</li>
 *   <li><b>failed shared evictions</b> - <em>alert on this</em>. A shared eviction that did not happen
 *       leaves every replica serving the stale value, which is strictly worse than the single-tier case;
 *       it is an incident, not a debug line;</li>
 *   <li><b>negative-cache hits</b> - how much work the negative TTL is saving, and the first place to
 *       look when a caller complains that a newly created entity is not visible yet;</li>
 *   <li><b>shape mismatches</b> - a shared entry written by a different release of the value type. A
 *       non-zero count during a rolling deploy means the key version was not bumped.</li>
 * </ul>
 */
public interface CacheMetrics {

    /**
     * Binds the per-cache gauges and Caffeine's own statistics.
     *
     * <p>Called once per cache, when it is built.
     *
     * @param settings the resolved configuration, whose name and purpose become meter tags
     * @param local    the Caffeine instance behind the local tier
     */
    void registerCache(CacheSettings settings, Cache<String, ?> local);

    /** A value was served without loading. {@code tier} is {@code local} or {@code shared}. */
    void recordHit(String cache, String tier);

    /** Nothing was held for the key, in any tier. */
    void recordMiss(String cache);

    /** A cached "there is no such value" answered the call without reaching the store. */
    void recordNegativeHit(String cache);

    /**
     * A loader ran.
     *
     * @param outcome {@code present}, {@code absent} or {@code failed} - the three cases
     *                {@link ru.ludwigandreas.cache.api.CacheLoad} distinguishes
     */
    void recordLoad(String cache, String outcome, Duration took);

    /** An entry was refreshed before its expiry, to spread the reload away from the expiry cliff. */
    void recordEarlyRefresh(String cache);

    /**
     * This replica lost the cluster-wide load lease.
     *
     * @param servedStale whether it had a stale value to serve, or had to load anyway
     */
    void recordLeaseLost(String cache, boolean servedStale);

    /**
     * Evicting the shared entry failed after every retry.
     *
     * <p>The one to alert on. See the interface javadoc.
     */
    void recordSharedEvictionFailure(String cache);

    /** A shared-tier operation failed; the call continued against the local tier alone. */
    void recordSharedError(String cache, String operation);

    /**
     * A shared entry was discarded because its value shape did not match this process's value type.
     *
     * <p>Counted and treated as a miss rather than deserialized. Non-zero during a rolling deploy means
     * the key namespace version was not bumped for a value-shape change.
     */
    void recordSharedShapeMismatch(String cache);

    /** Whether the shared tier is currently usable, for the gauge that says so. */
    void recordSharedTierAvailability(String cache, boolean available);
}
