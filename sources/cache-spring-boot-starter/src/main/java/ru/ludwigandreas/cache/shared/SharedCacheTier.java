package ru.ludwigandreas.cache.shared;

import ru.ludwigandreas.cache.api.CacheSettings;

/**
 * Creates one {@link SharedCacheView} per named cache.
 *
 * <p>A seam rather than a concrete Redis class, for two reasons that are not "in case we swap Redis":
 * the local-tier tests need no container and must not drag one in, and the two-tier behaviour that is
 * worth testing - what happens when a shared eviction fails - is only testable against a tier that can be
 * told to fail.
 */
public interface SharedCacheTier {

    /**
     * The view for one cache.
     *
     * <p>Performs the value-shape check for the cache's key namespace version. A version reused with a
     * differently-shaped value type throws here, at startup, which is the whole point: a bare
     * {@code version:} field somebody forgets to bump is worse than no versioning, because it converts a
     * loud failure into a silent one.
     *
     * @param settings  the resolved cache configuration
     * @param valueType the cached value's runtime type
     * @param <V>       the cached value type
     * @return the view
     * @throws ru.ludwigandreas.cache.error.CacheConfigurationException if this namespace version was last
     *         used with a differently-shaped value type
     */
    <V> SharedCacheView<V> viewOf(CacheSettings settings, Class<V> valueType);
}
