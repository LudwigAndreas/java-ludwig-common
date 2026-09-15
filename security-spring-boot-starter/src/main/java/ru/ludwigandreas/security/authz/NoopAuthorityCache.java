package ru.ludwigandreas.security.authz;

import java.util.Optional;

/**
 * Always-miss cache: every request re-resolves. Registered when Caffeine is absent from the classpath
 * or {@code ludwig.security.authorities.cache.enabled=false}.
 *
 * <p>Correct, just slower - and the failure mode of caching too eagerly (stale grants) is worse than
 * the failure mode of not caching (extra queries), so this is the safe default to fall back to.
 */
public class NoopAuthorityCache implements AuthorityCache {

    @Override
    public Optional<Authorities> get(PrincipalRef ref) {
        return Optional.empty();
    }

    @Override
    public void put(PrincipalRef ref, Authorities authorities) {
        // nothing to store
    }

    @Override
    public void evict(PrincipalRef ref) {
        // nothing to evict
    }

    @Override
    public void evictAll() {
        // nothing to evict
    }
}
