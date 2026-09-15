package ru.ludwigandreas.security.authz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Singular;

/**
 * What an {@link AuthorityResolver} answers with: everything a caller is entitled to, as this service
 * understands it.
 *
 * <p>Separate from {@link ru.ludwigandreas.security.principal.LudwigPrincipal} so the cache stores
 * only the part that can change without the identity changing. A role revocation invalidates this;
 * it does not invalidate the caller's token.
 *
 * @param roles       normalized to the {@code ROLE_} prefix by {@link #normalizeRole(String)}
 * @param permissions fine-grained entitlements such as {@code order:write}
 * @param attributes  grant values a {@link ru.ludwigandreas.security.data.DataScopeProvider} keys on
 *                    (region, branch, contract id, ...)
 */
@Builder
public record Authorities(
        @Singular Set<String> roles,
        @Singular Set<String> permissions,
        @Singular("attribute") Map<String, String> attributes) {

    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * Attribute carrying the caller's tenant. Set by a resolver that reads it from a store, and preferred
     * over the token's tenant claim - a claim is asserted by the issuer, the projection is what this
     * service actually believes, and only one of the two can be corrected without re-issuing tokens.
     */
    public static final String TENANT_ATTRIBUTE = "tenant";

    /**
     * Attribute carrying the partner a <em>user</em> acts on behalf of. A partner authenticating with its
     * own certificate needs no such attribute - it is its own subject.
     */
    public static final String PARTNER_ATTRIBUTE = "partner";
    private static final Authorities NONE = Authorities.builder().build();

    public Authorities {
        roles = normalize(roles);
        permissions = permissions == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * The answer for a caller this service knows nothing about. Authenticated but entitled to
     * nothing - which is the correct outcome for, say, a valid OIDC token belonging to an employee
     * who was never granted access to this particular service.
     */
    public static Authorities none() {
        return NONE;
    }

    public boolean isEmpty() {
        return roles.isEmpty() && permissions.isEmpty();
    }

    /**
     * Accepts roles written either way ({@code CATALOG_ADMIN} or {@code ROLE_CATALOG_ADMIN}) and
     * stores the prefixed form. Spring Security's {@code hasRole('X')} tests for {@code ROLE_X}, so
     * an unprefixed role loaded straight from a database column silently never matches - a failure
     * that looks like a permission problem and gets "fixed" by granting more.
     */
    public static String normalizeRole(String role) {
        String trimmed = role.trim();
        return trimmed.startsWith(ROLE_PREFIX) ? trimmed : ROLE_PREFIX + trimmed;
    }

    private static Set<String> normalize(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>(roles.size());
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                normalized.add(normalizeRole(role));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
