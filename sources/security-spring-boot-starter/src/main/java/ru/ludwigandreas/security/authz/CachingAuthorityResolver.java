package ru.ludwigandreas.security.authz;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.cache.api.CacheLoad;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.security.metrics.SecurityMetrics;

/**
 * The {@link AuthorityLookup} the filters use: an {@link AuthorityResolver} with the platform's
 * {@code authorities} cache and metrics wrapped around it.
 *
 * <p>Note what happens when the delegate throws: the request is failed, not served with empty authorities.
 * Substituting {@link Authorities#none()} for an unavailable role store would look like graceful degradation
 * but is the wrong trade here - it converts a database blip into a fleet-wide "everyone is suddenly
 * unauthorized", and, on any endpoint whose policy happens to be expressed as a denial, into a silent grant.
 * A 500 is the honest answer, and it is the one an SRE can alert on.
 *
 * <p>That is also why the loader reports a failure as {@link CacheLoad#failed(Throwable)} rather than as an
 * absence: the cache may cache an absence briefly and must never cache a failure. Caching one here would
 * turn a two-second database blip into a sustained authorization outage for exactly the principals that were
 * unlucky, and it would be self-reinforcing, because the cached failure suppresses the retry that would
 * notice the database came back.
 */
@Slf4j
@RequiredArgsConstructor
public class CachingAuthorityResolver implements AuthorityLookup {

    private final AuthorityResolver delegate;
    private final LudwigCache<PrincipalRef, Authorities> cache;
    private final SecurityMetrics metrics;

    @Override
    public Authorities lookup(PrincipalRef ref) {
        // The flag is how hit and miss are still told apart while the cache owns the load: the loader
        // body runs only on a miss, and only for the one caller that wins the per-key race. The cache
        // counts its own hits and misses too; these carry the principal type, which is what makes a
        // partner-shaped traffic change visible separately from a user-shaped one.
        AtomicBoolean loaded = new AtomicBoolean();
        Optional<Authorities> authorities = cache.get(ref, key -> {
            loaded.set(true);
            return CacheLoad.present(resolve(key));
        });

        if (loaded.get()) {
            metrics.recordAuthorityCacheMiss(ref.type().name());
        } else {
            metrics.recordAuthorityCacheHit(ref.type().name());
        }
        return authorities.orElseGet(Authorities::none);
    }

    private Authorities resolve(PrincipalRef ref) {
        Authorities resolved = delegate.resolve(ref);
        if (resolved == null) {
            log.warn("AuthorityResolver {} returned null for {}; treating as no grant",
                    delegate.getClass().getSimpleName(), ref.type());
            return Authorities.none();
        }
        return resolved;
    }
}
