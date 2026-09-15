package ru.ludwigandreas.security.principal;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.Singular;

/**
 * The single identity model every authorization decision in a service is made against, whoever the
 * caller is and however they authenticated.
 *
 * <p>Having one type here is the point of the whole module. A browser user arrives as a JWT minted
 * by the edge from a session cookie, a partner arrives as a client certificate Envoy terminated, a
 * peer service arrives as a SPIFFE workload identity - three completely different wire formats that
 * would otherwise leak into every {@code @PreAuthorize} expression and every query. They are
 * normalized into this record at the edge of the service, once, and nothing downstream needs to know
 * which door the caller came through unless it deliberately asks ({@link #type()}).
 *
 * <p><b>Roles are not taken from the token.</b> The OIDC provider issues identity, not entitlement:
 * the JWT carries {@code sub}, name and email, and {@link #roles()} is filled in by the service's own
 * {@link ru.ludwigandreas.security.authz.AuthorityResolver} from the role projection it maintains.
 * That keeps tokens small, lets a revoked role take effect within a cache TTL instead of a token
 * lifetime, and means a stolen token cannot carry elevated roles that were never granted.
 *
 * @param subject      stable, globally unique caller id - the OIDC {@code sub} for a user, the
 *                     partner id for a partner, the SPIFFE workload id for a service. Never a
 *                     username or email: those get reassigned, and an audit trail keyed on a
 *                     reassigned identifier attributes one principal's actions to another.
 * @param type         which door the caller came through
 * @param displayName  human-readable label for logs and UIs; never used in an authorization decision
 * @param tenantId     owning organization, when the deployment is multi-tenant; empty otherwise
 * @param roles        coarse entitlements, already normalized to the {@code ROLE_} prefix
 * @param permissions  fine-grained entitlements ({@code order:read}), when roles are too blunt
 * @param attributes   claim/grant values a {@link ru.ludwigandreas.security.data.DataScopeProvider}
 *                     may key on (branch, region, contract id). Read-only and never trusted from the
 *                     client - only what the resolver put there.
 */
@Builder(toBuilder = true)
public record LudwigPrincipal(
        String subject,
        PrincipalType type,
        String displayName,
        String tenantId,
        @Singular Set<String> roles,
        @Singular Set<String> permissions,
        @Singular("attribute") Map<String, String> attributes) implements Serializable {

    public LudwigPrincipal {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("principal subject must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("principal type must not be null");
        }
        // Normalized here, not only in Authorities: a principal built by hand - in a test, in a custom
        // authentication filter, in a background-job template - would otherwise carry bare role names,
        // and hasRole('X') tests for ROLE_X. The symptom is a caller who visibly holds the role and is
        // refused anyway, which reads as a permission bug and gets "fixed" by granting more.
        roles = roles == null
                ? Set.of()
                : roles.stream()
                        .filter(role -> role != null && !role.isBlank())
                        .map(ru.ludwigandreas.security.authz.Authorities::normalizeRole)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        roles = Collections.unmodifiableSet((LinkedHashSet<String>) roles);
        permissions = permissions == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean isType(PrincipalType expected) {
        return type == expected;
    }

    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    public Optional<String> tenant() {
        return Optional.ofNullable(tenantId).filter(value -> !value.isBlank());
    }
}
