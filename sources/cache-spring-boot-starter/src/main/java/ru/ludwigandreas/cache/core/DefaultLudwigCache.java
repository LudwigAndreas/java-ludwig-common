package ru.ludwigandreas.cache.core;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CacheLoad;
import ru.ludwigandreas.cache.api.CacheLoader;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.error.CacheLoadException;
import ru.ludwigandreas.cache.metrics.CacheMetrics;
import ru.ludwigandreas.cache.shared.SharedCacheView;
import ru.ludwigandreas.cache.shared.SharedEntry;

/**
 * The cache every module gets.
 *
 * <h2>Expiry is write-based, not access-based</h2>
 *
 * <p>{@code expireAfterWrite}, never {@code expireAfterAccess}, and this is the one Caffeine setting that
 * is not configurable. Both of the caches this module replaces had reached the same conclusion
 * independently and both had written down why, in different words:
 *
 * <ul>
 *   <li>for a security-purpose cache, access-based expiry lets a busy caller <b>hold a grant open
 *       indefinitely</b> simply by calling often. The TTL is a revocation window, and a window that
 *       resets on use is not a window;</li>
 *   <li>for a performance-purpose cache, an actively used key's entry would never expire - so the TTL
 *       would stop being a backstop for a missed eviction for exactly the keys most likely to notice
 *       one.</li>
 * </ul>
 *
 * <h2>Keyed by the rendered key</h2>
 *
 * <p>The local map is keyed by the string {@link CacheDefinition#keyRenderer()} produces, the same string
 * the shared tier uses. That makes a cross-replica invalidation an O(1) local invalidation, and it makes
 * the two tiers agree on identity by construction rather than by two implementations happening to match.
 * The price is that the renderer must be injective - two distinct keys rendering the same string would
 * serve one caller's value to another - which is why {@link CacheDefinition#keyRenderer()} says so and why
 * no default renderer is supplied.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@Slf4j
public class DefaultLudwigCache<K, V> implements LudwigCache<K, V> {

    private static final String TIER_LOCAL = "local";

    private static final String TIER_SHARED = "shared";

    private static final String OUTCOME_PRESENT = "present";

    private static final String OUTCOME_ABSENT = "absent";

    private static final String OUTCOME_FAILED = "failed";

    private final CacheSettings settings;
    private final CacheDefinition<K, V> definition;
    private final Cache<String, CacheEntry<K, V>> local;
    private final SharedCacheView<V> shared;
    private final CacheMetrics metrics;
    private final Clock clock;
    private final Executor refreshExecutor;

    /**
     * Rendered keys whose early refresh is already in flight.
     *
     * <p>Without it, every request arriving inside a refresh window on a hot key schedules its own refresh,
     * which is the herd this feature exists to prevent, moved onto a thread pool.
     */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    /**
     * @param settings        the resolved configuration
     * @param definition      the owning module's declaration, for the key renderer and the value type
     * @param local           the Caffeine instance, built by the registry with statistics on
     * @param shared          the shared tier, or {@code null} for a local-only cache
     * @param metrics         where hits, misses, loads and shared-tier failures are counted
     * @param clock           stamps entries; injected so a test can move time without sleeping
     * @param refreshExecutor runs early refreshes off the request thread
     */
    public DefaultLudwigCache(CacheSettings settings,
                              CacheDefinition<K, V> definition,
                              Cache<String, CacheEntry<K, V>> local,
                              SharedCacheView<V> shared,
                              CacheMetrics metrics,
                              Clock clock,
                              Executor refreshExecutor) {
        this.settings = settings;
        this.definition = definition;
        this.local = local;
        this.shared = shared;
        this.metrics = metrics;
        this.clock = clock;
        this.refreshExecutor = refreshExecutor;
        if (shared != null) {
            shared.subscribeInvalidations(this::onRemoteInvalidation);
        }
    }

    @Override
    public String name() {
        return settings.name();
    }

    @Override
    public Optional<V> get(K key) {
        String rendered = render(key);
        CacheEntry<K, V> entry = local.getIfPresent(rendered);
        if (entry != null && isFresh(entry)) {
            return recordAndUnwrap(entry, TIER_LOCAL);
        }
        Optional<CacheEntry<K, V>> promoted = readShared(key, rendered);
        if (promoted.isPresent()) {
            return recordAndUnwrap(promoted.get(), TIER_SHARED);
        }
        metrics.recordMiss(settings.name());
        return Optional.empty();
    }

    @Override
    public Optional<V> get(K key, CacheLoader<K, V> loader) {
        String rendered = render(key);
        CacheEntry<K, V> entry = local.getIfPresent(rendered);
        if (entry != null && isFresh(entry)) {
            considerEarlyRefresh(rendered, entry, loader);
            return recordAndUnwrap(entry, TIER_LOCAL);
        }
        Optional<CacheEntry<K, V>> promoted = readShared(key, rendered);
        if (promoted.isPresent()) {
            return recordAndUnwrap(promoted.get(), TIER_SHARED);
        }
        metrics.recordMiss(settings.name());
        return loadThrough(key, rendered, loader);
    }

    @Override
    public void put(K key, V value) {
        String rendered = render(key);
        CacheEntry<K, V> entry = new CacheEntry<>(key, value, clock.millis(), false);
        local.put(rendered, entry);
        writeShared(rendered, entry);
    }

    @Override
    public void evict(K key) {
        String rendered = render(key);
        local.invalidate(rendered);
        if (shared == null) {
            return;
        }
        // Local first, and the local eviction is not conditional on the shared one succeeding: this replica
        // being right is worth having even when the others cannot be told.
        if (!shared.delete(rendered)) {
            metrics.recordSharedEvictionFailure(settings.name());
        }
        shared.publishInvalidation(rendered);
    }

    @Override
    public void evictAll() {
        local.invalidateAll();
        if (shared == null) {
            return;
        }
        if (!shared.deleteAll()) {
            metrics.recordSharedEvictionFailure(settings.name());
        }
        shared.publishInvalidation(SharedCacheView.ALL_KEYS);
    }

    @Override
    public int evictByScan(Predicate<K> keyPredicate) {
        if (!settings.scanEviction()) {
            throw new IllegalStateException("Cache '" + settings.name() + "' did not declare"
                    + " scan-eviction: true, so evictByScan is refused. It walks the whole key set, which"
                    + " is affordable for a rare administrative call and not for anything else - declare"
                    + " ludwig.cache.caches." + settings.name() + ".scan-eviction: true if that is what"
                    + " this call site is. A cache with a shared tier cannot declare it at all.");
        }
        int before = local.asMap().size();
        local.asMap().values().removeIf(entry -> keyPredicate.test(entry.key()));
        return before - local.asMap().size();
    }

    @Override
    public long estimatedSize() {
        return local.estimatedSize();
    }

    private String render(K key) {
        String rendered = definition.keyRenderer().apply(key);
        if (rendered == null || rendered.isBlank()) {
            throw new IllegalArgumentException("Cache '" + settings.name() + "' rendered a blank key from "
                    + key + ". A key renderer must be total and injective; see"
                    + " CacheDefinition.keyRenderer().");
        }
        return rendered;
    }

    private Optional<V> recordAndUnwrap(CacheEntry<K, V> entry, String tier) {
        if (entry.negative()) {
            metrics.recordNegativeHit(settings.name());
            return Optional.empty();
        }
        metrics.recordHit(settings.name(), tier);
        return Optional.ofNullable(entry.value());
    }

    /**
     * Reads the shared tier and promotes a fresh entry into the local one.
     *
     * <p>Promotion is the point of having two tiers: a shared hit costs a network round trip, and paying it
     * once per replica per TTL is the arrangement; paying it on every read would make the shared tier a
     * second database rather than a cache.
     */
    private Optional<CacheEntry<K, V>> readShared(K key, String rendered) {
        if (shared == null) {
            return Optional.empty();
        }
        Optional<SharedEntry<V>> found = shared.read(rendered);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SharedEntry<V> sharedEntry = found.get();
        CacheEntry<K, V> entry = new CacheEntry<>(
                key, sharedEntry.value(), sharedEntry.writtenAtMillis(), sharedEntry.negative());
        if (!isFresh(entry)) {
            return Optional.empty();
        }
        local.put(rendered, entry);
        return Optional.of(entry);
    }

    private void writeShared(String rendered, CacheEntry<K, V> entry) {
        if (shared != null) {
            shared.write(rendered, new SharedEntry<>(entry.value(), entry.writtenAtMillis(),
                    entry.negative()));
        }
    }

    /**
     * Loads with per-key coalescing.
     *
     * <p>{@code asMap().compute} holds Caffeine's per-key lock for the duration of the mapping function, so
     * concurrent misses for one key run the loader once and the rest receive its result. Different keys are
     * unaffected: the lock is per key, not per cache. It is {@code compute} rather than
     * {@code get(key, fn)} because a stale-but-present entry must be replaced, and {@code get} would hand
     * the stale entry back without calling the loader.
     *
     * <p>A load that throws leaves the mapping untouched, which is what "a failure is never cached" means
     * mechanically: the stale entry, if there was one, is still there for the stale-read path, and the next
     * caller tries again.
     */
    private Optional<V> loadThrough(K key, String rendered, CacheLoader<K, V> loader) {
        CacheEntry<K, V> result = local.asMap().compute(rendered, (ignored, existing) -> {
            if (existing != null && isFresh(existing)) {
                return existing;
            }
            if (!takeLoadLease(rendered)) {
                boolean stale = existing != null && canServeStale(existing);
                metrics.recordLeaseLost(settings.name(), stale);
                if (stale) {
                    return existing;
                }
            }
            return loadEntry(key, rendered, loader, existing);
        });
        if (result == null || result.negative()) {
            return Optional.empty();
        }
        return Optional.ofNullable(result.value());
    }

    /**
     * Whether this replica may run the loader.
     *
     * <p>Always true unless the cluster-wide lease is enabled, and true even then when the shared tier is
     * unavailable: a lease that cannot be taken must not become a lease that is never granted. The loser
     * serves the stale value if it has one and loads anyway if it does not - blocking on a lease whose
     * holder may have crashed converts a slow load into a stalled request.
     */
    private boolean takeLoadLease(String rendered) {
        if (!settings.stampede().leaseEnabled() || shared == null) {
            return true;
        }
        boolean taken = shared.tryAcquireLoadLease(rendered);
        if (!taken) {
            log.debug("Cache '{}' lost the load lease for a key; serving stale if possible",
                    settings.name());
        }
        return taken;
    }

    private CacheEntry<K, V> loadEntry(K key, String rendered, CacheLoader<K, V> loader,
                                       CacheEntry<K, V> existing) {
        long startedAt = System.nanoTime();
        CacheLoad<V> loaded = invoke(key, loader);
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        if (loaded instanceof CacheLoad.Failed<V> failed) {
            metrics.recordLoad(settings.name(), OUTCOME_FAILED, took);
            throw failure(failed.cause());
        }
        if (loaded instanceof CacheLoad.Present<V> present) {
            metrics.recordLoad(settings.name(), OUTCOME_PRESENT, took);
            CacheEntry<K, V> entry = new CacheEntry<>(key, present.value(), clock.millis(), false);
            writeShared(rendered, entry);
            return entry;
        }
        metrics.recordLoad(settings.name(), OUTCOME_ABSENT, took);
        if (!settings.negative().enabled()) {
            // Returning null removes the mapping: an absence that is not cached must not linger as the
            // stale entry it replaced, or the next call would serve a value the store says is gone.
            return null;
        }
        CacheEntry<K, V> negative = new CacheEntry<>(key, null, clock.millis(), true);
        writeShared(rendered, negative);
        if (existing != null) {
            log.debug("Cache '{}' replaced an entry with a cached absence", settings.name());
        }
        return negative;
    }

    /**
     * Runs the loader, turning a thrown exception into {@link CacheLoad.Failed}.
     *
     * <p>The two are the same statement - "I could not find out" - and a loader is entitled to make it
     * either way. What neither may become is a cached negative: see {@link CacheLoad} for why caching a
     * failure turns a blip into an outage.
     */
    private CacheLoad<V> invoke(K key, CacheLoader<K, V> loader) {
        try {
            CacheLoad<V> loaded = loader.load(key);
            return loaded == null ? CacheLoad.absent() : loaded;
        } catch (RuntimeException e) {
            return CacheLoad.failed(e);
        }
    }

    private RuntimeException failure(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new CacheLoadException(settings.name(), cause);
    }

    private boolean isFresh(CacheEntry<K, V> entry) {
        return age(entry) <= settings.logicalTtl(entry.negative()).toMillis();
    }

    private boolean canServeStale(CacheEntry<K, V> entry) {
        if (!settings.purpose().allowsStaleReads()) {
            return false;
        }
        long limit = settings.logicalTtl(entry.negative()).plus(settings.stampede().staleGrace()).toMillis();
        return age(entry) <= limit;
    }

    private long age(CacheEntry<K, V> entry) {
        return clock.millis() - entry.writtenAtMillis();
    }

    /**
     * Refreshes an entry before it expires, sometimes.
     *
     * <h2>Why this is the stampede answer to prefer</h2>
     *
     * <p>Per-key coalescing is per process, so N replicas still issue N loads the moment a hot key expires
     * - and they do it simultaneously, because they all cached it at the same time, during the same deploy
     * or the same cold start. The herd is caused by <em>synchronised expiry</em>, and that is what this
     * removes: each replica independently rolls a die that becomes likelier as the entry ages, so the
     * reloads spread across the tail of the TTL instead of landing on its edge. No coordination, no new
     * failure mode, nothing to be unavailable.
     *
     * <p>It is safe for a security-purpose cache, which is worth stating because the lease is not: a
     * refresh re-reads the store and replaces the entry <em>within</em> its TTL, so the oldest value any
     * caller can see is still bounded by the TTL. The lease, by contrast, works by serving a value past its
     * expiry, which is exactly what a revocation window must not allow.
     *
     * <p>A refresh that fails changes nothing: the existing entry stays, and the ordinary expiry path will
     * load again. A failure here must not evict - that would convert a transient store problem into a
     * guaranteed synchronous miss for the next caller, which is the opposite of the intent.
     */
    private void considerEarlyRefresh(String rendered, CacheEntry<K, V> entry, CacheLoader<K, V> loader) {
        if (!settings.stampede().earlyRefreshEnabled() || entry.negative()) {
            return;
        }
        long ttlMillis = settings.ttl().toMillis();
        long thresholdMillis = (long) (ttlMillis * settings.stampede().earlyRefreshThreshold());
        long age = age(entry);
        if (age < thresholdMillis || ttlMillis <= thresholdMillis) {
            return;
        }
        double probability = (double) (age - thresholdMillis) / (ttlMillis - thresholdMillis);
        if (ThreadLocalRandom.current().nextDouble() >= probability) {
            return;
        }
        if (!refreshing.add(rendered)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                refresh(rendered, entry.key(), loader);
            } finally {
                refreshing.remove(rendered);
            }
        });
    }

    private void refresh(String rendered, K key, CacheLoader<K, V> loader) {
        CacheLoad<V> loaded = invoke(key, loader);
        if (loaded instanceof CacheLoad.Present<V> present) {
            CacheEntry<K, V> entry = new CacheEntry<>(key, present.value(), clock.millis(), false);
            local.put(rendered, entry);
            writeShared(rendered, entry);
            metrics.recordEarlyRefresh(settings.name());
            return;
        }
        if (loaded instanceof CacheLoad.Absent<V>) {
            local.invalidate(rendered);
            metrics.recordEarlyRefresh(settings.name());
            return;
        }
        log.debug("Cache '{}' early refresh failed; the existing entry is kept and the ordinary expiry"
                + " path will load again", settings.name());
    }

    /**
     * Another replica evicted a key.
     *
     * <p>Local only, and deliberately: the replica that published has already deleted the shared entry, and
     * a second delete per replica would multiply one eviction into as many Redis round trips as there are
     * pods.
     */
    private void onRemoteInvalidation(String rendered) {
        if (SharedCacheView.ALL_KEYS.equals(rendered)) {
            local.invalidateAll();
            return;
        }
        local.invalidate(rendered);
    }
}
