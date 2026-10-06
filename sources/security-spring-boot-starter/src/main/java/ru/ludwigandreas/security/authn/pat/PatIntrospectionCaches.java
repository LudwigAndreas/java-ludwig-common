package ru.ludwigandreas.security.authn.pat;

import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.pat.introspection.PatIntrospectionResponse;

/**
 * The introspection cache's declaration.
 *
 * <p>Without it the issuing service is called on <b>every request</b>, which turns it from a bounded
 * dependency into a per-request one with no cushion at all.
 *
 * <h2>The purpose is SECURITY, and it is the line that matters</h2>
 *
 * <p>For this authentication path the TTL <b>is</b> the revocation window: it is literally how long a
 * revoked token keeps authenticating. {@link CachePurpose#SECURITY} is what that means, and it is enforced
 * rather than advised - the cache module refuses a TTL above
 * {@code ludwig.cache.security-ttl-ceiling} at startup, naming the cache, and refuses to serve a stale
 * value past expiry.
 *
 * <p>{@code PERFORMANCE} would be the easy mistake. It is the right answer for most caches, it removes the
 * ceiling, and it switches on stale-while-revalidate - which reads as a throughput win and is precisely
 * wrong here, because what it extends is the window this TTL exists to bound, by a duration chosen for
 * throughput reasons somewhere else. Nothing would fail; a revoked token would simply keep working for
 * however long the stale window happened to be.
 *
 * <p>This TTL is also a term in the third revocation-window composition that
 * {@code RevocationWindowValidator} computes and bounds at startup, so the number is accounted for in the
 * total and not only ceilinged in isolation.
 *
 * <h2>Keyed on the digest, never the secret</h2>
 *
 * <p>The key is the SHA-256 of the presented secret. A cache key reaches metrics, a shared Redis tier's
 * key space, a heap dump and whatever an operator runs to inspect the cache - none of which should hold
 * credential material. A digest cannot be presented and cannot be reversed, so a reader of the key space
 * learns what they would learn from the token table: nothing they can authenticate with.
 */
public final class PatIntrospectionCaches {

    /** The cache name: the YAML key under {@code ludwig.cache.caches} and the {@code cache} meter tag. */
    public static final String NAME = "pat-introspection";

    /**
     * Thirty seconds.
     *
     * <p>The same figure the issuer's own verification cache uses, and for the same reason: this bounds how
     * long an explicitly revoked credential keeps authenticating, which is the action an operator takes
     * during an incident and expects to be fast.
     */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private static final long DEFAULT_MAXIMUM_SIZE = 20_000L;

    private PatIntrospectionCaches() {
    }

    /** The declaration this module publishes as a bean when the filter is enabled. */
    public static CacheDefinition<String, PatIntrospectionResponse> definition() {
        return CacheDefinition.<String, PatIntrospectionResponse>named(NAME, CachePurpose.SECURITY)
                .owner("security-spring-boot-starter")
                .defaultTtl(DEFAULT_TTL)
                .defaultMaximumSize(DEFAULT_MAXIMUM_SIZE)
                .valueType(PatIntrospectionResponse.class)
                .keyRenderer(digest -> digest)
                .build();
    }
}
