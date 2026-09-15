package ru.ludwigandreas.security.authz;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Bounded, TTL-expiring authority cache.
 *
 * <p>Deliberately per-instance and in-memory rather than shared (Redis): authorities are cheap to
 * recompute, and a shared cache turns an authorization decision into a network call to a store that
 * an attacker who reaches it could poison. The cost of the local choice is that a revocation has to
 * reach every instance - which it does, because eviction is driven by the same Kafka topic every
 * instance already consumes.
 *
 * <p>Expiry is {@code expireAfterWrite}, not {@code expireAfterAccess}: a busy caller must not be
 * able to hold a grant open indefinitely simply by calling often.
 */
public class CaffeineAuthorityCache implements AuthorityCache {

    private final Cache<PrincipalRef, Authorities> cache;

    public CaffeineAuthorityCache(Duration ttl, long maximumSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build();
    }

    @Override
    public Optional<Authorities> get(PrincipalRef ref) {
        return Optional.ofNullable(cache.getIfPresent(ref));
    }

    /**
     * Caffeine computes at most once per key: concurrent misses for the same principal block on the
     * first loader rather than each issuing its own query. Different principals are unaffected - the
     * lock is per key, not per cache.
     */
    @Override
    public Authorities get(PrincipalRef ref, Function<PrincipalRef, Authorities> loader) {
        return cache.get(ref, loader::apply);
    }

    @Override
    public void put(PrincipalRef ref, Authorities authorities) {
        cache.put(ref, authorities);
    }

    @Override
    public void evict(PrincipalRef ref) {
        cache.invalidate(ref);
    }

    @Override
    public void evictAll() {
        cache.invalidateAll();
    }
}
