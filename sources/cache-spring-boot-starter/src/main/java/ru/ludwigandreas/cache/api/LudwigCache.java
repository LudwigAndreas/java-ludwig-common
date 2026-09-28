package ru.ludwigandreas.cache.api;

import java.util.Optional;
import java.util.function.Predicate;
import ru.ludwigandreas.cache.tx.AfterCommitEviction;

/**
 * One named, typed cache.
 *
 * <p>Obtained from {@link LudwigCacheRegistry} with the {@link CacheDefinition} the owning module
 * declares. There is deliberately no constructor a module could call: the tiers, the TTL, the negative
 * policy and the stampede policy come from configuration, and a hand-built instance would be a cache
 * with none of the governance this module exists to apply - which is exactly the state the platform was
 * in when the same class had been written twice.
 *
 * <h2>Reading</h2>
 *
 * <p>{@link #get(Object, CacheLoader)} is the form to use. {@link #get(Object)} exists for a caller that
 * genuinely only wants to know whether the value is already held, and it is the rarer case.
 *
 * <h2>Writing and consistency</h2>
 *
 * <p>The TTL is the backstop, not the mechanism: consistency comes from eviction, and eviction that
 * follows a database write belongs after the commit - see {@link #evictAfterCommit(Object)}.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface LudwigCache<K, V> {

    /** The configured name: the YAML key, the meter tag, and the string in every log line. */
    String name();

    /**
     * What is held for this key, without loading anything.
     *
     * @param key the key
     * @return the value, or empty if the cache does not hold one - which a caller cannot tell from a
     *         cached "there is no such value"; use {@link #get(Object, CacheLoader)} if that matters
     */
    Optional<V> get(K key);

    /**
     * Resolves through the cache, loading on a miss.
     *
     * <h2>Why the loader form rather than get-then-put</h2>
     *
     * <p>Because it lets the cache coalesce concurrent misses for the same key. Without that, every
     * expiry of a hot key releases one load per in-flight request at once - a self-inflicted thundering
     * herd against the store, arriving exactly when traffic is highest. Coalescing is per key, so
     * different keys are unaffected.
     *
     * <p>Caffeine's compute-at-most-once is <b>per process</b>, so N replicas still issue N loads on a
     * cold key. Two configurable answers to that, in the order to prefer them: jittered early refresh
     * (on by default - it removes the synchronised-expiry cliff that causes the herd in the first place,
     * with no coordination and no new failure mode), and a short cluster-wide load lease
     * ({@code stampede.lease.enabled}, opt-in, for a genuinely expensive load).
     *
     * <p>Early refresh is available to a {@link CachePurpose#SECURITY} cache and the lease is not, and the
     * difference matters: a refresh re-reads the store and replaces the entry <em>within</em> its TTL, so
     * the oldest value any caller can see is still bounded by the TTL, whereas the lease works by serving a
     * value <em>past</em> its expiry - which is precisely what a revocation window must not allow.
     *
     * @param key    the key
     * @param loader invoked at most once per miss per process; see {@link CacheLoad} for why its return
     *               type distinguishes absent from failed
     * @return the value, or empty if the store says there is none
     * @throws ru.ludwigandreas.cache.error.CacheLoadException if the loader failed; a failure is never
     *                                                         cached, so the next call retries
     */
    Optional<V> get(K key, CacheLoader<K, V> loader);

    /**
     * Stores a value.
     *
     * <p>For a caller that has just computed the value for its own reasons. A write-through after a
     * database write is usually wrong: it publishes a value the transaction may still roll back. Evict
     * after commit and let the next read load.
     *
     * @param key   the key
     * @param value the value, never {@code null}
     */
    void put(K key, V value);

    /**
     * Drops one key, now, from every tier this cache uses.
     *
     * @param key the key
     */
    void evict(K key);

    /**
     * Drops one key from every tier once the current transaction commits.
     *
     * <p>The form to use after a write. See {@link AfterCommitEviction} for what evicting inside the
     * transaction does instead.
     *
     * @param key the key
     */
    default void evictAfterCommit(K key) {
        AfterCommitEviction.run(() -> evict(key));
    }

    /**
     * Drops everything, now.
     *
     * <p>A blunt instrument and the right one for a write at a role, tenant or platform scope, where
     * "who is affected" is a question the writer cannot answer without querying the directory. Those
     * writes are rare and administrative; the alternative is a query per write or a stale value for
     * everyone who inherits it.
     */
    void evictAll();

    /** Drops everything once the current transaction commits. */
    default void evictAllAfterCommit() {
        AfterCommitEviction.run(this::evictAll);
    }

    /**
     * Drops every key matching a predicate, by <b>scanning the whole key set</b>.
     *
     * <h2>The cost is in the name on purpose</h2>
     *
     * <p>This is O(n) in the number of entries, because Caffeine has one map and no secondary index.
     * {@code user-settings} needs it - a support tool that has a principal but not a tenant has to evict
     * that principal across every tenant they are cached under - and the previous implementation
     * offered it as {@code evict(PrincipalRef)}, an innocuous-looking overload beside the O(1)
     * {@code evict(SettingsSubject)}. A generic method that is secretly O(n) is a performance bug
     * waiting for its second caller, so the method that scans says that it scans.
     *
     * <h2>It is refused for the shared tier, at startup</h2>
     *
     * <p>A cache must declare {@code scan-eviction: true} to use this at all, and the startup validator
     * <b>fails the context</b> if a cache declares both {@code scan-eviction: true} and the shared tier.
     * Supporting it there would mean either a Redis {@code KEYS} sweep - which blocks the single-threaded
     * server and has taken production Redis instances down - or a real secondary index, which is a second
     * data structure to keep consistent with the first for one administrative call site. Refusing it, at
     * startup rather than on the unlucky request, is the honest third option.
     *
     * @param keyPredicate tested against every key currently held locally
     * @return how many entries were dropped
     * @throws IllegalStateException if this cache did not declare {@code scan-eviction: true}
     */
    int evictByScan(Predicate<K> keyPredicate);

    /**
     * Roughly how many entries are held locally.
     *
     * <p>Approximate, as Caffeine's own count is: entries whose TTL has passed may not have been
     * discarded yet. Exposed as a gauge, and the number worth watching against {@code maximum-size} - a
     * cache permanently at its ceiling is undersized, and its hit ratio will say so only later and less
     * clearly.
     */
    long estimatedSize();
}
