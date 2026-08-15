package ru.ludwigandreas.odatafilter.audit;

import java.time.Instant;
import java.util.Set;

/**
 * Published (as a plain Spring application event - no interface to implement) every time a
 * filter is successfully parsed, validated and translated to a predicate. Write an
 * {@code @EventListener} for this type to build an audit trail of who queried what, e.g. for
 * compliance logging in a multi-tenant system.
 */
public record FilterAppliedEvent(
        Class<?> entityType,
        String rawFilter,
        String resolvedPredicate,
        Set<String> callerRoles,
        Instant timestamp) {

    public FilterAppliedEvent(Class<?> entityType, String rawFilter, String resolvedPredicate, Set<String> callerRoles) {
        this(entityType, rawFilter, resolvedPredicate, callerRoles, Instant.now());
    }
}
