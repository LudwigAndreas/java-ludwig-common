package ru.ludwigandreas.usersettings.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Bounded, TTL-expiring settings cache.
 *
 * <p>Per-instance and in-memory rather than shared, for the same reason the authority cache is:
 * resolution is cheap to redo, and a shared cache would put a network hop in front of a read that a
 * page makes on every request. The cost is that a change has to reach every instance - which it
 * does, because every instance either performs the write itself or consumes the change event.
 *
 * <p>Expiry is {@code expireAfterWrite}, not {@code expireAfterAccess}. With access-based expiry an
 * actively-used subject's entry never expires, so the TTL would stop being a backstop for exactly
 * the subjects most likely to notice a missed event.
 */
public class CaffeineSettingsCache implements SettingsCache {

    private final Cache<SettingsSubject, ResolvedSettings> cache;

    /**
     * Builds the cache.
     *
     * @param ttl the backstop for a missed eviction, not the primary consistency path
     */
    public CaffeineSettingsCache(Duration ttl, long maximumSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build();
    }

    @Override
    public Optional<ResolvedSettings> get(SettingsSubject subject) {
        return Optional.ofNullable(cache.getIfPresent(subject));
    }

    /**
     * Caffeine computes at most once per key: concurrent misses for the same subject block on the
     * first loader rather than each issuing its own query. Different subjects are unaffected - the
     * lock is per key, not per cache.
     */
    @Override
    public ResolvedSettings get(SettingsSubject subject, Function<SettingsSubject, ResolvedSettings> loader) {
        return cache.get(subject, loader::apply);
    }

    @Override
    public void put(SettingsSubject subject, ResolvedSettings settings) {
        cache.put(subject, settings);
    }

    @Override
    public void evict(SettingsSubject subject) {
        cache.invalidate(subject);
    }

    /**
     * Scans the key set, because Caffeine has one map and no secondary index. That is acceptable
     * only because this form is the exception: every event-driven and write-driven eviction knows
     * its tenant and takes the single-key path above. A deployment where this became hot would be
     * one calling it from somewhere that does know the tenant.
     */
    @Override
    public void evict(PrincipalRef ref) {
        cache.asMap().keySet().removeIf(key -> key.ref().equals(ref));
    }

    @Override
    public void evictAll() {
        cache.invalidateAll();
    }

    /** Current entry count, after any pending evictions. Used by the cache-size gauge. */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
