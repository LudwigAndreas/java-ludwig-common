package ru.ludwigandreas.security.authz;

/**
 * Turns an authenticated identity into what it is allowed to do here.
 *
 * <p>This is the seam that keeps entitlement out of the token. The OIDC provider authenticates and
 * says who the caller is; this service decides what that caller may do, from its own projection of
 * the user/role stream. Three consequences worth stating, because they are the reason for the design:
 *
 * <ul>
 *   <li>Tokens stay small and stable. A user in forty groups does not carry forty claims through
 *       every hop, and adding a role does not require re-issuing tokens.</li>
 *   <li>Revocation is bounded by the cache TTL (seconds), not by the token lifetime. A JWT that
 *       carried its roles inside keeps them until it expires, however fast the directory reacts.</li>
 *   <li>Each service answers for its own authorization. The catalog's roles are the catalog's
 *       business; no shared, ever-growing global role list.</li>
 * </ul>
 *
 * <p>Implementations must be side-effect free and fast enough to sit on the request path; they are
 * normally wrapped in {@link CachingAuthorityResolver}. {@code identity-projection-spring-boot-starter}
 * ships the database-backed implementation fed by the OIDC Kafka stream; services with their own
 * store register a bean of this type instead and everything else in the module still applies.
 */
@FunctionalInterface
public interface AuthorityResolver {

    /**
     * @return the caller's entitlements, or {@link Authorities#none()} when this service has no grant
     *         on record. Must never throw for an unknown subject - not being in the projection is a
     *         normal answer ("no access"), not an error.
     */
    Authorities resolve(PrincipalRef ref);
}
