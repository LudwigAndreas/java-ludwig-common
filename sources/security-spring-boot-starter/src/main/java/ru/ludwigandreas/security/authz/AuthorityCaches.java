package ru.ludwigandreas.security.authz;

import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;

/**
 * The platform's {@code authorities} cache, declared.
 *
 * <h2>What used to be here</h2>
 *
 * <p>An {@code AuthorityCache} interface, a {@code CaffeineAuthorityCache} and a
 * {@code NoopAuthorityCache} - which were, to within their key and value types, the same three classes
 * {@code user-settings-spring-boot-starter} had written for its settings cache: the same five operations,
 * the same {@code (Duration ttl, long maximumSize)} constructor, the same paragraph explaining
 * {@code expireAfterWrite}, and the same paragraph making the thundering-herd argument in nearly the same
 * words. Nothing owned any of it, so the next module would have written it a third time. All three are gone;
 * {@code cache-spring-boot-starter} owns the behaviour and this class declares the one thing that was
 * genuinely local to security: what the TTL <em>means</em>.
 *
 * <h2>The TTL is a security window</h2>
 *
 * <p>That is why {@link CachePurpose#SECURITY} is declared here and why it matters more than any other
 * setting in this file. This TTL is how long a revoked role keeps working. Sizing it is a security decision,
 * not a performance one, and nothing at runtime will ever report that it is too long: no error, no log line,
 * no metric moves. The only symptom is a person retaining access they should not have, for as long as the
 * number somebody typed, and the person who notices is an auditor months later. Declaring the purpose is
 * what gets that number a startup check ({@code ludwig.cache.security-ttl-ceiling}) and what refuses the
 * stale-read affordances that would extend the window for throughput reasons.
 *
 * <p>The TTL is <em>not</em> the consistency mechanism. {@code identity-projection-spring-boot-starter}
 * evicts the moment a Kafka event changes a user's roles, so the TTL is the backstop for a dropped event or
 * a consumer that was down - which is what keeps a wrong grant from persisting until the next restart.
 */
public final class AuthorityCaches {

    /** The cache name: the YAML key under {@code ludwig.cache.caches} and the {@code cache} meter tag. */
    public static final String NAME = "authorities";

    /**
     * The module's suggested TTL.
     *
     * <p>Sixty seconds, unchanged from the value this module carried before the consolidation, so that
     * adopting the shared cache does not quietly change anybody's revocation window.
     */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    private AuthorityCaches() {
    }

    /**
     * The declaration this module publishes as a bean.
     *
     * @return the definition; a deployment overrides its TTL and size under
     *         {@code ludwig.cache.caches.authorities}
     */
    public static CacheDefinition<PrincipalRef, Authorities> definition() {
        return CacheDefinition.<PrincipalRef, Authorities>named(NAME, CachePurpose.SECURITY)
                .owner("security-spring-boot-starter")
                .defaultTtl(DEFAULT_TTL)
                .defaultMaximumSize(DEFAULT_MAXIMUM_SIZE)
                .valueType(Authorities.class)
                .keyRenderer(AuthorityCaches::renderKey)
                .build();
    }

    /**
     * Renders a principal into the tail of a shared key.
     *
     * <p>Type first and then subject, with a separator that cannot appear in a {@code PrincipalType} name, so
     * that the property {@link PrincipalRef} exists to guarantee survives into the shared tier: subject
     * namespaces are independent, and a partner id that happens to equal some user's {@code sub} must never
     * collide into one entry and hand one caller the other's roles. A renderer that dropped the type would
     * reintroduce exactly that, in the one tier where the collision is invisible.
     */
    private static String renderKey(PrincipalRef ref) {
        return ref.type().name() + ':' + ref.subject();
    }
}
