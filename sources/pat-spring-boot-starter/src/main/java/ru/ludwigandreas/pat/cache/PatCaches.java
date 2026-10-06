package ru.ludwigandreas.pat.cache;

import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;

/**
 * The verification cache's declaration.
 *
 * <p>Verification on every exchange would otherwise be a database round trip per request, on the one
 * endpoint that is both on the hot path and the system's only brute-force target.
 *
 * <h2>The purpose declaration is the one thing no check can infer, and it is {@code SECURITY}</h2>
 *
 * <p>This is the single most consequential line in this class, so it is worth stating what it buys and what
 * {@code PERFORMANCE} would have quietly taken away.
 *
 * <p>{@link CachePurpose#SECURITY} means <b>the TTL is the revocation window</b> - it is literally how long a
 * revoked token keeps working. Three things follow, all enforced by the cache module rather than advised:
 *
 * <ul>
 *   <li>the default TTL is on the scale of seconds, not minutes;</li>
 *   <li>a TTL above {@code ludwig.cache.security-ttl-ceiling} <b>fails startup, naming this cache</b>;</li>
 *   <li>serving a stale value past expiry is <b>refused</b>, as is the cluster-wide load lease whose losing
 *       replica serves stale.</li>
 * </ul>
 *
 * <p>Declaring {@code PERFORMANCE} would have been the easy mistake: it is the right answer for most caches,
 * it removes the ceiling, and it switches on stale-while-revalidate - which reads as a pure throughput win
 * and is exactly wrong here, because what it extends is the window this TTL exists to bound, by a duration
 * chosen for throughput reasons somewhere else. Nothing in the code would have failed. The symptom would
 * have been a revoked token that kept working for however long the stale window happened to be.
 *
 * <p>This cache's TTL is also a term in the composed revocation window that
 * {@code RevocationWindowValidator} computes and bounds at startup, so the number is not only ceilinged in
 * isolation but accounted for in the total.
 *
 * <h2>Keyed on the digest, never on the secret</h2>
 *
 * <p>The cache key is the presented secret's SHA-256, not the secret. A cache key ends up in metrics, in a
 * shared Redis tier's key space, in a heap dump and in whatever an operator runs to inspect the cache - none
 * of which should ever hold credential material.
 */
public final class PatCaches {

    /** The cache name: the YAML key under {@code ludwig.cache.caches} and the {@code cache} meter tag. */
    public static final String NAME = "pat-verification";

    /**
     * Thirty seconds.
     *
     * <p>Shorter than the authority cache's sixty, because the two bound different things. The authority
     * cache bounds how long a <em>role change</em> takes to apply; this bounds how long an explicitly
     * revoked credential keeps authenticating, which is the action an operator takes during an incident and
     * expects to be fast.
     */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private static final long DEFAULT_MAXIMUM_SIZE = 20_000L;

    private PatCaches() {
    }

    /**
     * Renders a cache key for the shared tier.
     *
     * <p>Required by the cache module even for a local-only cache, and its validator says why: declaring it
     * up front means adding {@code shared} to this cache's tiers stays a change to one YAML file rather than
     * a code change. Worth having been forced to think about, because the thing that would land in a shared
     * key space here is credential-adjacent.
     *
     * <p>The key is the <b>digest</b> of the presented secret, which is what this cache is keyed on anyway -
     * never the secret. That distinction is what makes a shared tier acceptable: a digest cannot be
     * presented and cannot be reversed, so an operator or an attacker reading the Redis key space learns
     * exactly what they would learn from reading the {@code ludwig_pat} table, which is nothing they can
     * authenticate with. A cache keyed on the raw secret would have put a usable credential in a key space
     * that appears in metrics, in {@code SCAN} output and in a heap dump.
     *
     * <p>Returned unchanged rather than re-hashed. Re-hashing would look more careful and would buy nothing:
     * the input is already a one-way digest, and a second round would only make an operator unable to
     * correlate a cache entry with the row it came from while debugging.
     */
    private static String renderKey(String secretDigest) {
        return secretDigest;
    }

    /**
     * The declaration this module publishes as a bean.
     *
     * @return the definition; a deployment overrides its TTL and size under
     *         {@code ludwig.cache.caches.pat-verification}
     */
    public static CacheDefinition<String, CachedVerification> definition() {
        return CacheDefinition.<String, CachedVerification>named(NAME, CachePurpose.SECURITY)
                .owner("pat-spring-boot-starter")
                .defaultTtl(DEFAULT_TTL)
                .defaultMaximumSize(DEFAULT_MAXIMUM_SIZE)
                .valueType(CachedVerification.class)
                .keyRenderer(PatCaches::renderKey)
                .build();
    }
}
