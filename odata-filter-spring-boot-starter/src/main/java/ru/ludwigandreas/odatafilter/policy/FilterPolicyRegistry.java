package ru.ludwigandreas.odatafilter.policy;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;

/**
 * Builds and caches the effective {@link EntityFilterPolicy} for a given entity class by
 * reflecting over its (and its to-one associations') {@code @Filterable}/{@code @FilterPolicy}
 * annotations. Resolution happens lazily, on first use per class, so there is nothing to
 * configure or scan up front - adding this library as a dependency and annotating entities is
 * sufficient.
 */
public class FilterPolicyRegistry {

    private final ODataFilterProperties defaults;
    private final ConcurrentMap<Class<?>, EntityFilterPolicy> cache = new ConcurrentHashMap<>();

    public FilterPolicyRegistry(ODataFilterProperties defaults) {
        this.defaults = defaults;
    }

    public EntityFilterPolicy policyFor(Class<?> entityType) {
        return cache.computeIfAbsent(entityType, this::build);
    }

    private EntityFilterPolicy build(Class<?> entityType) {
        FilterPolicy override = entityType.getAnnotation(FilterPolicy.class);
        int maxDepth = resolve(override == null ? -1 : override.maxDepth(), defaults.getMaxDepth());
        int maxPageSize = resolve(override == null ? -1 : override.maxPageSize(), defaults.getMaxPageSize());
        int defaultPageSize = resolve(override == null ? -1 : override.defaultPageSize(), defaults.getDefaultPageSize());
        int maxNestedPropertyDepth = resolve(
                override == null ? -1 : override.maxNestedPropertyDepth(), defaults.getMaxNestedPropertyDepth());

        Map<String, FilterFieldPolicy> fields = new LinkedHashMap<>();
        collect(entityType, "", 1, maxNestedPropertyDepth, fields, new HashSet<>());
        return new EntityFilterPolicy(
                entityType, maxDepth, maxPageSize, defaultPageSize, maxNestedPropertyDepth, Map.copyOf(fields));
    }

    private void collect(Class<?> type, String prefix, int depth, int maxDepth,
                          Map<String, FilterFieldPolicy> out, Set<Class<?>> pathTypes) {
        if (!pathTypes.add(type)) {
            return; // guard against cyclic associations (e.g. Department/Manager/Department/...)
        }
        try {
            for (Field field : allFields(type)) {
                Filterable filterable = field.getAnnotation(Filterable.class);
                String name = filterable != null && !filterable.name().isBlank() ? filterable.name() : field.getName();
                String path = prefix.isEmpty() ? name : prefix + "/" + name;

                if (filterable != null) {
                    out.put(path, new FilterFieldPolicy(
                            path,
                            field.getType(),
                            Set.of(filterable.ops()),
                            Set.of(filterable.roles()),
                            filterable.sortable()));
                }

                if (depth < maxDepth && isToOneAssociation(field)) {
                    collect(field.getType(), path, depth + 1, maxDepth, out, pathTypes);
                }
            }
        } finally {
            pathTypes.remove(type);
        }
    }

    private static boolean isToOneAssociation(Field field) {
        return field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class);
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(List.of(c.getDeclaredFields()));
        }
        return fields;
    }

    private static int resolve(int overrideValue, int defaultValue) {
        return overrideValue >= 0 ? overrideValue : defaultValue;
    }
}
