package ru.ludwigandreas.odatafilter.security;

import java.util.Set;

/**
 * Fallback used when nothing else is configured: every caller has no roles, so any field
 * declaring {@code @Filterable(roles = ...)} is unreachable until a real resolver is supplied.
 */
public class AnonymousFilterPrincipalResolver implements FilterPrincipalResolver {

    @Override
    public Set<String> resolveRoles() {
        return Set.of();
    }
}
