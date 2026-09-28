package ru.ludwigandreas.cache.test;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.cache.config.CacheConfigurationValidator;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.config.CacheSettingsResolver;
import ru.ludwigandreas.cache.config.DefaultLudwigCacheRegistry;
import ru.ludwigandreas.cache.metrics.CacheMetrics;
import ru.ludwigandreas.cache.metrics.NoopCacheMetrics;
import ru.ludwigandreas.cache.shared.SharedCacheTier;

/**
 * Builds a real cache without a Spring context.
 *
 * <p>Ships in the main jar rather than a test jar, the same arrangement
 * {@code rest-client-spring-boot-starter}'s {@code @LudwigRestClientTest} slice uses, so that a consuming
 * module gets it with the starter. Before it existed, a module whose collaborator takes a cache had two
 * options in a unit test: mock the cache, which tests nothing about caching and hides the difference between
 * a hit and a miss, or boot a context, which is a hundred times slower than the thing under test. Both
 * happened, in the two modules this starter replaced.
 *
 * <h2>Refreshes run on the calling thread</h2>
 *
 * <p>The refresh executor here is {@code Runnable::run}. In production an early refresh must never run on the
 * request thread - that is the latency it exists to remove - but in a test a refresh that happens on some
 * other thread at some other time is a flake, and asserting on it would mean sleeping. Same code, different
 * executor, and the difference is stated rather than discovered.
 */
public final class LudwigCacheTestSupport {

    /** Refreshes run inline, so a test never has to sleep to observe one. */
    private static final Executor SAME_THREAD = Runnable::run;

    private LudwigCacheTestSupport() {
    }

    /**
     * A registry over default configuration: every cache local-only, on its module's suggested TTL.
     *
     * @param definitions the caches to declare
     * @return the registry
     */
    public static LudwigCacheRegistry registry(CacheDefinition<?, ?>... definitions) {
        return registry(new CacheProperties(), Clock.systemUTC(), new NoopCacheMetrics(), null, definitions);
    }

    /**
     * A registry over the given configuration.
     *
     * @param properties  the {@code ludwig.cache} tree, built in the test
     * @param definitions the caches to declare
     * @return the registry
     */
    public static LudwigCacheRegistry registry(CacheProperties properties,
                                               CacheDefinition<?, ?>... definitions) {
        return registry(properties, Clock.systemUTC(), new NoopCacheMetrics(), null, definitions);
    }

    /**
     * A registry with everything substitutable.
     *
     * @param properties  the {@code ludwig.cache} tree
     * @param clock       a fixed or offsettable clock, so a test can expire an entry without sleeping
     * @param metrics     a recording double, to assert that a shared eviction failure was counted
     * @param sharedTier  a fake or a Redis-backed tier, or {@code null} for local-only
     * @param definitions the caches to declare
     * @return the registry
     */
    public static LudwigCacheRegistry registry(CacheProperties properties,
                                               Clock clock,
                                               CacheMetrics metrics,
                                               SharedCacheTier sharedTier,
                                               CacheDefinition<?, ?>... definitions) {
        List<CacheDefinition<?, ?>> declared = Arrays.asList(definitions);
        CacheSettingsResolver resolver = new CacheSettingsResolver(properties);
        CacheConfigurationValidator validator =
                new CacheConfigurationValidator(properties, resolver, "test-application");
        return new DefaultLudwigCacheRegistry(declared, resolver, validator, sharedTier, metrics, clock,
                SAME_THREAD);
    }

    /**
     * One cache over default configuration.
     *
     * @param definition the cache to build
     * @param <K>        the key type
     * @param <V>        the value type
     * @return the cache
     */
    public static <K, V> LudwigCache<K, V> cache(CacheDefinition<K, V> definition) {
        return registry(definition).cache(definition);
    }

    /**
     * One cache over the given configuration.
     *
     * @param properties the {@code ludwig.cache} tree
     * @param definition the cache to build
     * @param <K>        the key type
     * @param <V>        the value type
     * @return the cache
     */
    public static <K, V> LudwigCache<K, V> cache(CacheProperties properties,
                                                 CacheDefinition<K, V> definition) {
        return registry(properties, definition).cache(definition);
    }
}
