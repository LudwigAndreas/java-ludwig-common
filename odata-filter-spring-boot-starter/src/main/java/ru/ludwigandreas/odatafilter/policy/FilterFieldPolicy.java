package ru.ludwigandreas.odatafilter.policy;

import java.util.Set;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;

/** Resolved, effective policy for one {@code /}-joined property path of an entity. */
public record FilterFieldPolicy(
        String path,
        Class<?> javaType,
        Set<FilterOperator> allowedOperators,
        Set<String> allowedRoles,
        boolean sortable) {

    public boolean permitsOperator(FilterOperator operator) {
        return allowedOperators.contains(operator);
    }

    /** Empty {@link #allowedRoles()} means no extra role restriction beyond reaching the endpoint at all. */
    public boolean permitsRoles(Set<String> callerRoles) {
        return allowedRoles.isEmpty() || callerRoles.stream().anyMatch(allowedRoles::contains);
    }
}
