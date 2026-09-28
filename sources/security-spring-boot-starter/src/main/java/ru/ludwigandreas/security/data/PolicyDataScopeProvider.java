package ru.ludwigandreas.security.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Role-to-scope policy read from configuration:
 *
 * <pre>{@code
 * ludwig.security.data:
 *   default-access: NONE          # anything not listed below
 *   policies:
 *     order:
 *       read:
 *         ROLE_ORDER_ADMIN: ALL
 *         ROLE_ORDER_AGENT: OWN
 *         ROLE_TENANT_VIEWER: TENANT
 *         ROLE_PARTNER: PARTNER+TENANT
 *       write:
 *         ROLE_ORDER_ADMIN: ALL
 *         ROLE_ORDER_AGENT: OWN
 * }</pre>
 *
 * <p>Grants are {@code ALL}, {@code NONE}, or {@code +}-joined dimensions that must all hold
 * ({@code PARTNER+TENANT} = "rows of my partner, within my tenant"). A caller holding several matching
 * roles gets the union. Expressions are parsed at startup by {@link ScopePolicies}, so a malformed one
 * fails the deploy.
 *
 * <p>Configuration rather than code because this is the layer operations needs to be able to read and
 * change during an incident without a release. Anything that needs a lookup - per-user grant rows,
 * delegation, time-boxed access - belongs in a second {@link DataScopeProvider} bean instead; they
 * compose.
 */
@Slf4j
@RequiredArgsConstructor
public class PolicyDataScopeProvider implements DataScopeProvider {

    private final ScopePolicies policies;
    private final SecurityProperties properties;

    @Override
    public DataScope scopeFor(LudwigPrincipal principal, String resourceType, String action) {
        Map<String, ScopeGrant> rolePolicies = policies.grantsFor(resourceType, action);
        if (rolePolicies.isEmpty()) {
            return defaultAccess();
        }

        DataScope scope = DataScope.none();
        for (Map.Entry<String, ScopeGrant> entry : rolePolicies.entrySet()) {
            if (!principal.hasRole(entry.getKey())) {
                continue;
            }
            scope = scope.union(toScope(principal, entry.getValue(), resourceType, action));
            if (scope.isUnrestricted()) {
                return scope;
            }
        }
        return scope;
    }

    private DataScope defaultAccess() {
        return properties.getData().getDefaultAccess() == SecurityProperties.DefaultAccess.ALL
                ? DataScope.all()
                : DataScope.none();
    }

    /**
     * A dimension the principal has no value for collapses the whole grant to {@link DataScope#none()}
     * rather than being dropped from the conjunction. Dropping it would widen the grant - a token with
     * no tenant claim would turn "my tenant's rows" into "all rows", which is precisely the kind of
     * quiet escalation a missing claim should never cause.
     */
    private DataScope toScope(LudwigPrincipal principal, ScopeGrant grant, String resourceType, String action) {
        switch (grant.kind()) {
            case ALL:
                return DataScope.all();
            case NONE:
                return DataScope.none();
            default:
                break;
        }

        Map<ScopeDimension, Set<String>> restriction = new LinkedHashMap<>();
        for (ScopeDimension dimension : grant.dimensions()) {
            Optional<String> value = valueFor(principal, dimension);
            if (value.isEmpty()) {
                log.debug("Principal {} has no value for scope dimension '{}' required by policy "
                                + "{}.{}; denying instead of widening the grant",
                        principal.type(), dimension, resourceType, action);
                return DataScope.none();
            }
            restriction.put(dimension, Set.of(value.get()));
        }
        return restriction.isEmpty() ? DataScope.none() : DataScope.restrictedTo(restriction);
    }

    private Optional<String> valueFor(LudwigPrincipal principal, ScopeDimension dimension) {
        if (ScopeDimension.OWNER.equals(dimension)) {
            return Optional.of(principal.subject());
        }
        if (ScopeDimension.TENANT.equals(dimension)) {
            return principal.tenant();
        }
        if (ScopeDimension.PARTNER.equals(dimension)) {
            // A partner calling on its own behalf is its own partner id; a user acting for a partner
            // carries it as a grant attribute put there by the AuthorityResolver, never by the client.
            return principal.isType(PrincipalType.PARTNER)
                    ? Optional.of(principal.subject())
                    : principal.attribute(ScopeDimension.PARTNER.name());
        }
        return principal.attribute(dimension.name());
    }
}
