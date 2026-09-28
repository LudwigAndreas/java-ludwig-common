package ru.ludwigandreas.cache.core;

/**
 * What the local tier holds for one key.
 *
 * <h2>Why the entry wraps the value instead of being the value</h2>
 *
 * <p>Two of this module's behaviours need to know things Caffeine's own map cannot tell them.
 *
 * <ul>
 *   <li><b>The write time.</b> Caffeine's {@code expireAfterWrite} is a physical bound and this module
 *       needs a logical one as well: an entry is <em>fresh</em> for the TTL and may still be
 *       <em>present</em> for the stale grace after it, which is what makes jittered early refresh
 *       possible - a refresh needs to know an entry is at 80% of its life, and an expired entry is simply
 *       gone. So Caffeine is configured with the longer bound and freshness is computed from here.</li>
 *   <li><b>Whether this records an absence.</b> A negative entry has its own, shorter TTL and must answer
 *       a read as "no such value" rather than as a miss - a miss would call the loader, which is the
 *       hammering this exists to stop.</li>
 * </ul>
 *
 * <h2>Why the original key is here</h2>
 *
 * <p>The local map is keyed by the <em>rendered</em> key, the same string the shared tier uses, so that a
 * cross-replica invalidation carrying a rendered key is an O(1) local invalidation rather than a scan of
 * the whole key set. That leaves {@link ru.ludwigandreas.cache.api.LudwigCache#evictByScan} with strings
 * where it needs typed keys, so the typed key rides along in the entry. It is one reference per entry, and
 * it is what keeps the O(n) operation honest instead of impossible.
 *
 * @param key             the typed key, for {@code evictByScan}
 * @param value           the value, or {@code null} when {@code negative}
 * @param writtenAtMillis when this entry was created, for logical freshness
 * @param negative        whether this records "there is no such value"
 * @param <K>             the key type
 * @param <V>             the value type
 */
public record CacheEntry<K, V>(K key, V value, long writtenAtMillis, boolean negative) {
}
