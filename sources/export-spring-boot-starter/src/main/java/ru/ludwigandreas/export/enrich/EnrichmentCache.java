package ru.ludwigandreas.export.enrich;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The values a run has already fetched, for the life of that run.
 *
 * <h2>Load-bearing, not an optimisation</h2>
 *
 * <p>At the design point a key recurs across windows - a thousand orders belonging to one customer,
 * spread over five hundred windows - and the difference between caching and not is roughly two
 * orders of magnitude in partner calls. A report that would make four thousand calls makes four
 * hundred thousand without this, against a partner sized for interactive traffic. That is the
 * difference between a report and an incident.
 *
 * <h2>Absence is cached too</h2>
 *
 * <p>A key the partner does not know is stored as a sentinel rather than left out. Without it, every
 * window containing that key asks again, and a report over data with a few thousand orphaned
 * references spends most of its partner budget re-learning the same nothing. This is the same
 * insight as {@code MissingPolicy}, one level down: not-found is an answer, and an answer is worth
 * remembering.
 *
 * <h2>Scoped to one run, bounded by entries</h2>
 *
 * <p>Per run rather than shared across runs, because a report is a point-in-time statement: two
 * runs an hour apart should see the partner's data as it was when each of them started, and a cache
 * that outlived a run would make the second one silently a mixture. Bounded by entry count rather
 * than by weight because the engine's memory budget is expressed in entries everywhere else, and a
 * ceiling nobody can compare to the window size is a ceiling nobody sets correctly.
 */
public final class EnrichmentCache {

    /** What a key the partner does not know is stored as; see the class comment. */
    private static final Object ABSENT = new Object();

    private final Cache<Key, Object> cache;
    private long hits;
    private long misses;

    /**
     * Creates the cache for one run.
     *
     * @param maximumEntries the ceiling across every stage of this run
     */
    public EnrichmentCache(int maximumEntries) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maximumEntries))
                .recordStats()
                .build();
    }

    /**
     * Splits a stage's keys into the ones already known and the ones that have to be fetched.
     *
     * @param stageName the stage, which scopes the keys
     * @param keys      the distinct keys this window needs
     * @param <K>       the key type
     * @param <V>       the value type
     * @return what was found, and what still has to be asked for
     */
    public <K, V> Lookup<K, V> lookup(String stageName, Collection<K> keys) {
        Map<K, V> resolved = new HashMap<>();
        List<K> unresolvedKeys = new ArrayList<>();
        List<K> absentKeys = new ArrayList<>();
        for (K key : keys) {
            Object cached = cache.getIfPresent(new Key(stageName, key));
            if (cached == null) {
                unresolvedKeys.add(key);
                misses++;
            } else if (cached == ABSENT) {
                absentKeys.add(key);
                hits++;
            } else {
                @SuppressWarnings("unchecked")
                V value = (V) cached;
                resolved.put(key, value);
                hits++;
            }
        }
        return new Lookup<>(resolved, List.copyOf(unresolvedKeys), List.copyOf(absentKeys));
    }

    /** Remembers what a partner answered for a set of keys, including the ones it did not know. */
    public <K, V> void store(String stageName, Collection<K> asked, Map<K, V> found) {
        for (K key : asked) {
            V value = found.get(key);
            cache.put(new Key(stageName, key), value == null ? ABSENT : value);
        }
    }

    /** How many lookups this run answered without asking a partner. */
    public long hits() {
        return hits;
    }

    /** How many lookups this run had to ask a partner about. */
    public long misses() {
        return misses;
    }

    /** How many entries Caffeine has evicted, which is what a cache sized too small looks like. */
    public long evictions() {
        return cache.stats().evictionCount();
    }

    /**
     * What a window's keys were already known to be.
     *
     * @param resolved   keys whose value is already known
     * @param unresolved keys nothing is known about, which have to be fetched
     * @param absent     keys the partner has already said it does not know
     * @param <K>        the key type
     * @param <V>        the value type
     */
    public record Lookup<K, V>(Map<K, V> resolved, List<K> unresolved, List<K> absent) {

        /** How many of this window's keys the cache answered. */
        public int hitCount() {
            return resolved.size() + absent.size();
        }
    }

    /**
     * A cache entry's identity.
     *
     * <p>Scoped by stage as well as by key, because two stages legitimately look up different things
     * under the same identifier - a customer id means one thing to the identity service and another
     * to the billing one - and a cache that conflated them would serve one stage's value to the
     * other, silently and only when the ids happened to collide.
     */
    private record Key(String stage, Object value) {
    }
}
