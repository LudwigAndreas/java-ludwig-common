package ru.ludwigandreas.security.authz;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * Fallback resolver used when no real one is registered: it returns whatever the authentication layer
 * already put on the principal and looks nothing up.
 *
 * <p>It exists so the module is usable in a service that genuinely has no role projection yet - a
 * spike, a test, a service whose only callers are peer services with a static grant. In a deployment
 * with the OIDC Kafka stream this bean is replaced by
 * {@code identity-projection-spring-boot-starter}'s database-backed resolver, and
 * {@code ludwig.security.authorities.require-resolver=true} makes starting <em>without</em> one a
 * startup failure, so a production service cannot end up here by accident.
 */
@RequiredArgsConstructor
public class TokenClaimAuthorityResolver implements AuthorityResolver {

    private final Map<PrincipalRef, Authorities> staticGrants;

    public TokenClaimAuthorityResolver() {
        this(Map.of());
    }

    @Override
    public Authorities resolve(PrincipalRef ref) {
        return staticGrants.getOrDefault(ref, Authorities.none());
    }

    /** Convenience for tests and for the static service-to-service grants in configuration. */
    public static Authorities of(Set<String> roles) {
        return Authorities.builder().roles(roles).build();
    }
}
