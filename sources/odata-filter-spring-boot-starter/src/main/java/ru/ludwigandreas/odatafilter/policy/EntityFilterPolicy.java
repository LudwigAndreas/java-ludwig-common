package ru.ludwigandreas.odatafilter.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.odatafilter.parser.OrderByTerm;

/**
 * Effective, fully-resolved filter policy for one entity type - global defaults merged with any {@code @FilterPolicy}
 * override.
 *
 * @param defaultOrderBy server-side ordering appended to the caller's {@code $orderby}; empty when
 *                       the entity declares none, in which case paging over this entity has no
 *                       guaranteed row order
 */
public record EntityFilterPolicy(
        Class<?> entityType,
        int maxDepth,
        int maxPageSize,
        int defaultPageSize,
        int maxNestedPropertyDepth,
        Map<String, FilterFieldPolicy> fields,
        List<OrderByTerm> defaultOrderBy) {

    public EntityFilterPolicy {
        fields = Map.copyOf(fields);
        defaultOrderBy = List.copyOf(defaultOrderBy);
    }

    public Optional<FilterFieldPolicy> field(String path) {
        return Optional.ofNullable(fields.get(path));
    }
}
