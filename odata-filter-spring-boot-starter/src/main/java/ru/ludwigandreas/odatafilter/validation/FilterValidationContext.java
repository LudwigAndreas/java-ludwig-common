package ru.ludwigandreas.odatafilter.validation;

import java.util.Set;
import ru.ludwigandreas.odatafilter.ast.FilterNode;

/**
 * Everything a {@link FilterValidator} needs to judge whether a parsed filter should be allowed
 * through, beyond the built-in depth/field/role/page-size checks the library always applies.
 */
public record FilterValidationContext(
        Class<?> entityType,
        String rawFilter,
        FilterNode root,
        Set<String> callerRoles) {
}
