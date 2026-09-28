package ru.ludwigandreas.cache.api;

import java.util.Set;

/**
 * Resolves a {@link CacheDefinition} into the configured {@link LudwigCache}.
 *
 * <p>One bean, injected by the module that owns each cache. Caches are built once and memoized, so
 * asking twice for the same definition returns the same instance and two collaborators in the same
 * service share one cache rather than each getting their own copy of the same entries.
 *
 * <h2>The startup guarantee</h2>
 *
 * <p>Both directions of the name/declaration relationship are checked, and both checks are the reason
 * this indirection exists instead of a constructor:
 *
 * <ul>
 *   <li>a definition whose name has no {@code ludwig.cache.caches.<name>} block still works, using the
 *       module's defaults - a service should not have to write YAML to get a working authority cache;</li>
 *   <li>a {@code ludwig.cache.caches.<name>} block matching no declared definition <b>fails startup</b>.
 *       That is the typo, and it is otherwise silent: {@code authoritys:} would be bound, ignored, and
 *       the operator would conclude the TTL they set had no effect on anything.</li>
 * </ul>
 */
public interface LudwigCacheRegistry {

    /**
     * The cache for this definition, built on first call.
     *
     * @param definition the owning module's declaration
     * @param <K>        the key type
     * @param <V>        the value type
     * @return the configured cache; a disabled cache
     *         ({@code ludwig.cache.caches.<name>.enabled=false}) returns an always-miss instance rather
     *         than {@code null}, so turning a cache off never changes a call site
     * @throws ru.ludwigandreas.cache.error.CacheConfigurationException if a cache of this name was
     *         already built from a different definition - two modules claiming one name would otherwise
     *         share a key space with two value types in it
     */
    <K, V> LudwigCache<K, V> cache(CacheDefinition<K, V> definition);

    /** Every declared cache name, for tooling, actuator output and error messages. */
    Set<String> names();

    /** Whether a cache of this name has been declared by some module. */
    boolean contains(String name);
}
