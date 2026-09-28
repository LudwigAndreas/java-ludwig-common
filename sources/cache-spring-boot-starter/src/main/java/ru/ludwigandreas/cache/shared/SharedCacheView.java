package ru.ludwigandreas.cache.shared;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * One named cache's window onto the shared tier.
 *
 * <p>A view rather than a set of methods taking a cache name, so that the key prefix, the value type, the
 * shape fingerprint and the namespace version are bound once instead of being threaded through every
 * call - which is where a mismatch between two of them would otherwise hide.
 *
 * <p>Every method is best-effort and none of them throws: a shared tier that cannot be reached must
 * degrade to local-only rather than fail a business call, because the shared tier is a performance
 * arrangement and an unavailable one is a slower service, not a broken one. The exception is eviction,
 * which reports failure rather than swallowing it - see {@link #delete(String)}.
 *
 * @param <V> the cached value type
 */
public interface SharedCacheView<V> {

    /** The invalidation payload meaning "drop everything for this cache". */
    String ALL_KEYS = "*";

    /**
     * Whether the tier is usable right now.
     *
     * <p>{@code false} when Redis is unreachable, and also when the tier is <em>unverified</em>: if the
     * value-shape check could not run at startup because Redis was down, the tier does not serve or store
     * until it has run. Serving from an unverified namespace is the exact hazard the version exists to
     * prevent, so the fallback is local-only rather than optimistic.
     */
    boolean isAvailable();

    /**
     * Reads one entry.
     *
     * @param renderedKey the key as {@link ru.ludwigandreas.cache.api.CacheDefinition#keyRenderer()}
     *                    produced it
     * @return the entry, or empty on a miss, on an unavailable tier, or on a value whose shape does not
     *         match this process's value type - the last of those is counted as a shape mismatch and
     *         discarded rather than deserialized
     */
    Optional<SharedEntry<V>> read(String renderedKey);

    /**
     * Writes one entry, with the Redis expiry set from the cache's physical TTL.
     *
     * @param renderedKey the rendered key
     * @param entry       what to store
     */
    void write(String renderedKey, SharedEntry<V> entry);

    /**
     * Deletes one entry.
     *
     * <p>Retried internally. The return value is what the caller counts: a shared eviction that did not
     * happen leaves <em>every</em> replica serving the stale value, which is strictly worse than the
     * single-tier case where only the writing replica was wrong. It is an incident, not a debug line.
     *
     * @param renderedKey the rendered key
     * @return whether the delete reached Redis
     */
    boolean delete(String renderedKey);

    /**
     * Deletes every entry in this cache's namespace.
     *
     * <p>Implemented with a cursored {@code SCAN}, never {@code KEYS}: {@code KEYS} blocks the
     * single-threaded server for the whole sweep and has taken production Redis instances down. It is
     * still an administrative operation rather than a hot path - it walks the keyspace - which is one more
     * reason {@code evictAll} is documented as blunt.
     *
     * @return whether the sweep completed
     */
    boolean deleteAll();

    /**
     * Tries to become the one replica in the cluster that loads this key.
     *
     * <p>{@code SET NX PX}: either this replica takes the lease or another holds it. A loser does not
     * block - it serves the stale value if it has one, and loads anyway if it does not, because blocking
     * on a lease whose holder may have crashed converts a slow load into a stalled request.
     *
     * @param renderedKey the rendered key
     * @return whether this replica took the lease
     */
    boolean tryAcquireLoadLease(String renderedKey);

    /**
     * Announces that one key has been evicted, so other replicas drop their local copies.
     *
     * <p>The third part of a two-tier eviction, and the one that is easy to forget: deleting the shared
     * entry does nothing about the local copies every other replica is holding. Without this, local TTL is
     * the only bound on them.
     *
     * @param renderedKey the rendered key, or {@link #ALL_KEYS} for an {@code evictAll}
     */
    void publishInvalidation(String renderedKey);

    /**
     * Subscribes to this cache's invalidation channel.
     *
     * @param listener receives each rendered key another replica evicted, or {@link #ALL_KEYS}
     */
    void subscribeInvalidations(Consumer<String> listener);
}
