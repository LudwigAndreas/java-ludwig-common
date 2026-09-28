package ru.ludwigandreas.cache.api;

/**
 * Produces the value for a key the cache does not hold.
 *
 * <p>Invoked at most once per miss <em>per process</em>, and for the single caller that wins the
 * per-key race - see {@link LudwigCache#get(Object, CacheLoader)} for why that coalescing is the
 * default rather than an option, and what it still does not solve across replicas.
 *
 * <p>The return type is {@link CacheLoad} and not {@code Optional} on purpose: the cache has to tell
 * "there is no such value" (cacheable, briefly) from "I could not find out" (never cacheable). See
 * {@link CacheLoad} for what caching a failure does to an outage.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@FunctionalInterface
public interface CacheLoader<K, V> {

    /**
     * Loads the value for one key.
     *
     * <p>May throw instead of returning {@link CacheLoad.Failed}; the two are treated identically.
     *
     * @param key the key that missed
     * @return what the store said, never {@code null}
     */
    CacheLoad<V> load(K key);
}
