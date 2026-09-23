package ru.ludwigandreas.odatafilter.policy;

import java.util.List;
import java.util.Set;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;

/**
 * Resolved, effective policy for one {@code /}-joined property path of an entity.
 *
 * @param roleRequirements one entry per {@code @Filterable} crossed on the way to this path - the
 *                         association fields traversed, then the field itself. Each entry is an
 *                         "any of these roles" set, and a caller must satisfy every entry, so a
 *                         nested path is never easier to reach than the association leading to it.
 */
public record FilterFieldPolicy(
        String path,
        Class<?> javaType,
        Set<FilterOperator> allowedOperators,
        List<Set<String>> roleRequirements,
        boolean sortable) {

    public FilterFieldPolicy {
        roleRequirements = List.copyOf(roleRequirements);
    }

    public boolean permitsOperator(FilterOperator operator) {
        return allowedOperators.contains(operator);
    }

    /** An empty role set means no extra restriction beyond reaching the endpoint at all. */
    public boolean permitsRoles(Set<String> callerRoles) {
        return roleRequirements.stream()
                .allMatch(required -> required.isEmpty() || callerRoles.stream().anyMatch(required::contains));
    }
}
