package ru.ludwigandreas.security.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.config.SecurityProperties;

/**
 * The {@code ludwig.security.data.policies} tree, compiled once at startup.
 *
 * <p>Compiling rather than reading the raw maps per request does three things. Grant expressions are
 * parsed once, so a typo fails the deploy rather than a request. Role names are normalized to the
 * {@code ROLE_} prefix once, instead of allocating a prefixed string on every check of every role.
 * And the result is an immutable structure that {@code DataScopePolicyValidator} can walk at startup to
 * confirm every resource and dimension a policy names is actually mapped.
 */
public final class ScopePolicies {

    /** resource -> action -> normalized role -> grant. */
    private final Map<String, Map<String, Map<String, ScopeGrant>>> byResource;

    private ScopePolicies(Map<String, Map<String, Map<String, ScopeGrant>>> byResource) {
        this.byResource = byResource;
    }

    public static ScopePolicies compile(SecurityProperties properties) {
        boolean strict = properties.getData().isStrictPolicyTokens();
        Map<String, Map<String, Map<String, ScopeGrant>>> compiled = new LinkedHashMap<>();

        properties.getData().getPolicies().forEach((resource, byAction) -> {
            Map<String, Map<String, ScopeGrant>> actions = new LinkedHashMap<>();
            if (byAction != null) {
                byAction.forEach((action, byRole) -> {
                    Map<String, ScopeGrant> roles = new LinkedHashMap<>();
                    if (byRole != null) {
                        byRole.forEach((role, grant) -> roles.put(
                                Authorities.normalizeRole(role),
                                ScopeGrant.parse(grant, strict, resource + "." + action + "." + role)));
                    }
                    actions.put(action, Collections.unmodifiableMap(roles));
                });
            }
            compiled.put(resource, Collections.unmodifiableMap(actions));
        });

        return new ScopePolicies(Collections.unmodifiableMap(compiled));
    }

    /** @return normalized role -> grant for this resource and action; empty when no policy applies */
    public Map<String, ScopeGrant> grantsFor(String resourceType, String action) {
        Map<String, Map<String, ScopeGrant>> actions = byResource.get(resourceType);
        if (actions == null) {
            return Map.of();
        }
        return actions.getOrDefault(action, Map.of());
    }

    public Set<String> resources() {
        return byResource.keySet();
    }

    /** Every dimension any grant on this resource needs - what its mapping has to be able to bind. */
    public Set<ScopeDimension> dimensionsUsedBy(String resourceType) {
        Set<ScopeDimension> dimensions = new LinkedHashSet<>();
        byResource.getOrDefault(resourceType, Map.of())
                .values()
                .forEach(byRole -> byRole.values()
                        .forEach(grant -> dimensions.addAll(grant.dimensions())));
        return dimensions;
    }

    public boolean isEmpty() {
        return byResource.isEmpty();
    }
}
