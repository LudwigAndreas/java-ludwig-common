package ru.ludwigandreas.cache.shared;

/**
 * What the shared tier stores: a value, or a recorded absence, plus when it was written.
 *
 * <p>The write time is stored rather than relied upon from Redis's own TTL because freshness here is a
 * logical property, not a physical one. An entry may be physically present and logically stale - that is
 * what makes stale-while-revalidate and lease-loser reads possible - and Redis can only express one
 * expiry per key.
 *
 * @param value            the value, or {@code null} for a recorded absence
 * @param writtenAtMillis  epoch milliseconds, from the same clock the local tier uses
 * @param negative         whether this records "there is no such value"
 * @param <V>              the value type
 */
public record SharedEntry<V>(V value, long writtenAtMillis, boolean negative) {
}
