package ru.ludwigandreas.odatafilter.policy;

import java.util.Map;
import java.util.Optional;

/** Effective, fully-resolved filter policy for one entity type - global defaults merged with any {@code @FilterPolicy} override. */
public record EntityFilterPolicy(
        Class<?> entityType,
        int maxDepth,
        int maxPageSize,
        int defaultPageSize,
        int maxNestedPropertyDepth,
        Map<String, FilterFieldPolicy> fields) {

    public Optional<FilterFieldPolicy> field(String path) {
        return Optional.ofNullable(fields.get(path));
    }
}
