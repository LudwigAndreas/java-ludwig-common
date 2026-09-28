package ru.ludwigandreas.odatafilter.security;

import java.util.Set;

/**
 * Reports the roles of the caller making the current filtered request, used to enforce
 * {@code @Filterable(roles = ...)}. If Spring Security is on the classpath, a resolver backed by
 * {@code SecurityContextHolder} is auto-registered; otherwise implement this yourself (e.g. to
 * read roles from a gateway-injected header) and expose it as a bean to enable role-based field
 * restrictions. With no resolver at all, every caller is treated as having no roles, so any
 * field declaring {@code roles = ...} becomes unreachable by anyone.
 */
public interface FilterPrincipalResolver {

    Set<String> resolveRoles();
}
