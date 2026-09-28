package ru.ludwigandreas.cache.core;

import java.util.Optional;
import java.util.function.Predicate;
import ru.ludwigandreas.cache.api.CacheLoad;
import ru.ludwigandreas.cache.api.CacheLoader;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.error.CacheLoadException;

/**
 * An always-miss cache: every read loads.
 *
 * <p>Returned for a cache configured {@code enabled: false}, so that turning a cache off is a change to
 * one YAML line and never a change to a call site. That is the whole reason it exists rather than the
 * registry returning {@code null} or the owning module branching.
 *
 * <h2>One of these, not one per module</h2>
 *
 * <p>There were two before this module: {@code NoopAuthorityCache} and {@code NoopSettingsCache},
 * identical but for their types, each existing so that its module worked with Caffeine absent from the
 * classpath. A starter named "cache" cannot sensibly have Caffeine missing, so the classpath case is gone
 * and what remains is the configuration case - which is one behaviour and therefore one class.
 *
 * <p>Correct, just slower. For a security-purpose cache that is also the safe direction to fail in: the
 * failure mode of caching too eagerly is a stale grant, and the failure mode of not caching is extra
 * queries.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class NoopLudwigCache<K, V> implements LudwigCache<K, V> {

    private final String name;

    /** @param name the configured cache name, so metrics and logs still identify it */
    public NoopLudwigCache(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.empty();
    }

    @Override
    public Optional<V> get(K key, CacheLoader<K, V> loader) {
        CacheLoad<V> loaded = loader.load(key);
        if (loaded instanceof CacheLoad.Present<V> present) {
            return Optional.of(present.value());
        }
        if (loaded instanceof CacheLoad.Failed<V> failed) {
            Throwable cause = failed.cause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CacheLoadException(name, cause);
        }
        return Optional.empty();
    }

    @Override
    public void put(K key, V value) {
        // nothing to store
    }

    @Override
    public void evict(K key) {
        // nothing to evict
    }

    @Override
    public void evictAll() {
        // nothing to evict
    }

    @Override
    public int evictByScan(Predicate<K> keyPredicate) {
        return 0;
    }

    @Override
    public long estimatedSize() {
        return 0;
    }
}
