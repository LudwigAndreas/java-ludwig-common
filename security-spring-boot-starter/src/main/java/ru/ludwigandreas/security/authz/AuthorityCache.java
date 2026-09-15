package ru.ludwigandreas.security.authz;

import java.util.Optional;
import java.util.function.Function;

/**
 * Short-lived cache in front of an {@link AuthorityResolver}.
 *
 * <p>Sizing it is a security decision, not a performance one: the TTL is the window in which a
 * revoked role still works. Seconds, not minutes. The eviction hooks exist so the window can be
 * closed early - {@code identity-projection-spring-boot-starter} calls {@link #evict(PrincipalRef)}
 * the moment a Kafka event changes a user's roles, which turns the TTL into a backstop for missed
 * events rather than the normal path to consistency.
 */
public interface AuthorityCache {

    Optional<Authorities> get(PrincipalRef ref);

    /**
     * Resolve through the cache, loading on a miss.
     *
     * <p>Preferred over a {@code get}-then-{@code put} pair because it lets an implementation coalesce
     * concurrent misses for the same principal. Without that, every expiry of a hot subject releases one
     * lookup per in-flight request at once - a self-inflicted thundering herd against the role store,
     * arriving exactly when traffic is highest. The default below is the naive behaviour, correct but
     * uncoalesced; {@link CaffeineAuthorityCache} overrides it.
     *
     * @param loader invoked at most once per miss; must not return {@code null}
     */
    default Authorities get(PrincipalRef ref, Function<PrincipalRef, Authorities> loader) {
        return get(ref).orElseGet(() -> {
            Authorities loaded = loader.apply(ref);
            put(ref, loaded);
            return loaded;
        });
    }

    void put(PrincipalRef ref, Authorities authorities);

    /** Called when a role change for exactly this principal is known to have happened. */
    void evict(PrincipalRef ref);

    /** Called on a projection rebuild or a role-definition change that can affect every caller. */
    void evictAll();
}
