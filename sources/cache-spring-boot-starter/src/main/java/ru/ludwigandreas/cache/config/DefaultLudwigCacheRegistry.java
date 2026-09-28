package ru.ludwigandreas.cache.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.cache.core.CacheEntry;
import ru.ludwigandreas.cache.core.DefaultLudwigCache;
import ru.ludwigandreas.cache.core.NoopLudwigCache;
import ru.ludwigandreas.cache.error.CacheConfigurationException;
import ru.ludwigandreas.cache.error.UnknownCacheException;
import ru.ludwigandreas.cache.metrics.CacheMetrics;
import ru.ludwigandreas.cache.shared.SharedCacheTier;
import ru.ludwigandreas.cache.shared.SharedCacheView;

/**
 * Builds each declared cache once and hands out the same instance thereafter.
 *
 * <h2>Why validation happens in this constructor</h2>
 *
 * <p>Because this is the only point at which the whole set of declarations is known and nothing has yet been
 * built from any of them. A {@code @PostConstruct} elsewhere could run after a module's
 * {@code @Bean} method had already been handed a cache, which would make the startup check a startup
 * <em>report</em>. {@link CacheDefinition} beans are plain data with no dependency on this registry, so
 * Spring creates all of them first and this ordering is guaranteed rather than hoped for.
 *
 * <h2>Why the Caffeine instance is built here and not by the module</h2>
 *
 * <p>Because that is the rule this module exists to make enforceable. Two modules had each built their own
 * {@code Caffeine.newBuilder()}, each reaching the same conclusions about {@code expireAfterWrite} and
 * writing the same paragraph about why, and neither calling {@code recordStats()} - so the platform's two
 * hot-path caches had no observable hit ratio at all. An ArchUnit rule now fails the build on a
 * {@code Caffeine} builder outside this module; this is the one place that remains.
 */
@Slf4j
public class DefaultLudwigCacheRegistry implements LudwigCacheRegistry {

    private final CacheSettingsResolver resolver;
    private final SharedCacheTier sharedTier;
    private final CacheMetrics metrics;
    private final Clock clock;
    private final Executor refreshExecutor;

    private final Map<String, CacheDefinition<?, ?>> declared = new LinkedHashMap<>();
    private final Map<String, LudwigCache<?, ?>> built = new ConcurrentHashMap<>();

    /**
     * @param definitions     every cache declared by a module on the classpath
     * @param resolver        merges each declaration with the deployment's YAML
     * @param validator       refuses an unsafe combination before anything is built
     * @param sharedTier      the Redis tier, or {@code null} when no Redis is configured
     * @param metrics         where hits, misses, loads and shared-tier failures are counted
     * @param clock           stamps entries; injected so a test can move time without sleeping
     * @param refreshExecutor runs early refreshes off the request thread
     */
    public DefaultLudwigCacheRegistry(Collection<CacheDefinition<?, ?>> definitions,
                                      CacheSettingsResolver resolver,
                                      CacheConfigurationValidator validator,
                                      SharedCacheTier sharedTier,
                                      CacheMetrics metrics,
                                      Clock clock,
                                      Executor refreshExecutor) {
        this.resolver = resolver;
        this.sharedTier = sharedTier;
        this.metrics = metrics;
        this.clock = clock;
        this.refreshExecutor = refreshExecutor;
        for (CacheDefinition<?, ?> definition : definitions) {
            CacheDefinition<?, ?> previous = declared.put(definition.name(), definition);
            if (previous != null && previous.valueType() != definition.valueType()) {
                throw new CacheConfigurationException("Two modules declare a cache named '"
                        + definition.name() + "': " + previous.owner() + " and " + definition.owner()
                        + ". They would share one key space, one TTL and two value types; rename one.");
            }
        }
        validator.validate(declared.values());
    }

    @Override
    @SuppressWarnings("unchecked") // Keyed by name, and a name claimed twice was refused in the constructor.
    public <K, V> LudwigCache<K, V> cache(CacheDefinition<K, V> definition) {
        CacheDefinition<?, ?> registered = declared.get(definition.name());
        if (registered == null) {
            throw new UnknownCacheException(definition.name(), declared.keySet());
        }
        if (registered.valueType() != definition.valueType()) {
            throw new CacheConfigurationException("Cache '" + definition.name() + "' was declared by "
                    + registered.owner() + " holding " + registered.valueType().getName()
                    + ", and is being resolved by " + definition.owner() + " as holding "
                    + definition.valueType().getName() + ". One name is one key space, one TTL and one"
                    + " serialized shape; rename one of the two.");
        }
        // The DECLARED definition is what the cache is built from, not the one passed in. A module that
        // resolves with a freshly constructed but equivalent definition still gets the cache the platform
        // validated, and the key renderer behind one name therefore cannot be two different functions.
        CacheDefinition<K, V> declaredDefinition = (CacheDefinition<K, V>) registered;
        return (LudwigCache<K, V>) built.computeIfAbsent(definition.name(),
                ignored -> build(declaredDefinition));
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(declared.keySet());
    }

    @Override
    public boolean contains(String name) {
        return declared.containsKey(name);
    }

    private <K, V> LudwigCache<K, V> build(CacheDefinition<K, V> definition) {
        CacheSettings settings = resolver.resolve(definition);
        if (!settings.enabled()) {
            log.info("Cache '{}' is disabled; every read will resolve through its loader", settings.name());
            return new NoopLudwigCache<>(settings.name());
        }

        Cache<String, CacheEntry<K, V>> local = Caffeine.newBuilder()
                .expireAfterWrite(settings.physicalTtl())
                .maximumSize(settings.maximumSize())
                .recordStats()
                .build();

        SharedCacheView<V> shared = null;
        if (settings.usesSharedTier()) {
            if (sharedTier == null) {
                throw new CacheConfigurationException("Cache '" + settings.name() + "' lists the shared"
                        + " tier, but no Redis is available: spring-data-redis is an optional dependency of"
                        + " cache-spring-boot-starter and no RedisConnectionFactory is on the context. Add"
                        + " spring-boot-starter-data-redis and configure spring.data.redis, or drop"
                        + " 'shared' from ludwig.cache.caches." + settings.name() + ".tiers.");
            }
            shared = sharedTier.viewOf(settings, definition.valueType());
        }

        metrics.registerCache(settings, local);
        log.info("Cache '{}' ({}): ttl {}, maximum size {}, tiers {}{}{}", settings.name(),
                settings.purpose().id(), settings.ttl(), settings.maximumSize(), settings.tiers(),
                settings.negative().enabled() ? ", negative " + settings.negative().ttl() : "",
                settings.stampede().earlyRefreshEnabled() ? ", early refresh" : "");
        return new DefaultLudwigCache<>(settings, definition, local, shared, metrics, clock,
                refreshExecutor);
    }
}
