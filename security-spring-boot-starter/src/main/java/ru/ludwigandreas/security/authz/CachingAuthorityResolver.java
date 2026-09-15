package ru.ludwigandreas.security.authz;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.metrics.SecurityMetrics;

/**
 * The {@link AuthorityLookup} the filters use: an {@link AuthorityResolver} with an
 * {@link AuthorityCache} and metrics wrapped around it.
 *
 * <p>Note what happens when the delegate throws: the request is failed, not served with empty
 * authorities. Substituting {@link Authorities#none()} for an unavailable role store would look like
 * graceful degradation but is the wrong trade here - it converts a database blip into a fleet-wide
 * "everyone is suddenly unauthorized", and, on any endpoint whose policy happens to be expressed as a
 * denial, into a silent grant. A 500 is the honest answer, and it is the one an SRE can alert on.
 */
@Slf4j
@RequiredArgsConstructor
public class CachingAuthorityResolver implements AuthorityLookup {

    private final AuthorityResolver delegate;
    private final AuthorityCache cache;
    private final SecurityMetrics metrics;

    @Override
    public Authorities lookup(PrincipalRef ref) {
        // The flag is how hit and miss are still told apart while the cache owns the load: the loader
        // body runs only on a miss, and only for the one caller that wins the per-key race.
        AtomicBoolean loaded = new AtomicBoolean();
        Authorities authorities = cache.get(ref, key -> {
            loaded.set(true);
            return resolve(key);
        });

        if (loaded.get()) {
            metrics.recordAuthorityCacheMiss(ref.type().name());
        } else {
            metrics.recordAuthorityCacheHit(ref.type().name());
        }
        return authorities == null ? Authorities.none() : authorities;
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
