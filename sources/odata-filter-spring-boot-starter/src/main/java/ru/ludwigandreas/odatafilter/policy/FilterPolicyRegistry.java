package ru.ludwigandreas.odatafilter.policy;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.parser.ODataOrderByParser;
import ru.ludwigandreas.odatafilter.parser.OrderByTerm;

/**
 * Builds and caches the effective {@link EntityFilterPolicy} for a given entity class by
 * reflecting over its (and its annotated to-one associations') {@code @Filterable}/
 * {@code @FilterPolicy} annotations. Resolution happens lazily, on first use per class, so there
 * is nothing to configure or scan up front - adding this library as a dependency and annotating
 * entities is sufficient.
 *
 * <p>Both halves of the walk are opt-in: a field is filterable only if it carries
 * {@code @Filterable}, and an association is traversable only if <em>it</em> carries
 * {@code @Filterable} too. See {@link Filterable} for why the second half is not automatic.
 */
public class FilterPolicyRegistry {

    private final ODataFilterProperties defaults;
    private final ODataOrderByParser orderByParser = new ODataOrderByParser();
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
        int defaultPageSize = resolve(
                override == null ? -1 : override.defaultPageSize(), defaults.getDefaultPageSize());
        int maxNestedPropertyDepth = resolve(
                override == null ? -1 : override.maxNestedPropertyDepth(), defaults.getMaxNestedPropertyDepth());

        Map<String, FilterFieldPolicy> fields = new LinkedHashMap<>();
        collect(entityType, "", List.of(), 1, maxNestedPropertyDepth, fields, new HashSet<>());
        List<OrderByTerm> defaultOrderBy = defaultOrderBy(entityType, override);

        return new EntityFilterPolicy(
                entityType, maxDepth, maxPageSize, defaultPageSize, maxNestedPropertyDepth,
                Map.copyOf(fields), defaultOrderBy);
    }

    /**
     * @param inheritedRoles the role requirement of every association traversed to get here, in
     *                       order, so that opening a nested field can only ever narrow access
     */
    private void collect(Class<?> type, String prefix, List<Set<String>> inheritedRoles, int depth, int maxDepth,
                          Map<String, FilterFieldPolicy> out, Set<Class<?>> pathTypes) {
        if (!pathTypes.add(type)) {
            return; // guard against cyclic associations (e.g. Department/Manager/Department/...)
        }
        try {
            for (Field field : allFields(type)) {
                Filterable filterable = field.getAnnotation(Filterable.class);
                if (filterable == null) {
                    continue;
                }
                String name = filterable.name().isBlank() ? field.getName() : filterable.name();
                String path = prefix.isEmpty() ? name : prefix + "/" + name;

                // An association is a path segment, not a value: it opens its target's fields
                // rather than becoming one, so `department eq 'x'` stays unparseable.
                if (isToOneAssociation(field)) {
                    if (depth < maxDepth) {
                        collect(field.getType(), path, append(inheritedRoles, filterable.roles()),
                                depth + 1, maxDepth, out, pathTypes);
                    }
                    continue;
                }

                out.put(path, new FilterFieldPolicy(
                        path,
                        field.getType(),
                        Set.of(filterable.ops()),
                        append(inheritedRoles, filterable.roles()),
                        filterable.sortable()));
            }
        } finally {
            pathTypes.remove(type);
        }
    }

    private static List<Set<String>> append(List<Set<String>> inherited, String[] roles) {
        if (roles.length == 0) {
            return inherited;
        }
        List<Set<String>> combined = new ArrayList<>(inherited);
        combined.add(Set.of(roles));
        return List.copyOf(combined);
    }

    /**
     * Parses {@code @FilterPolicy#defaultOrderBy} and checks every path against the entity's mapped
     * fields. Deliberately not checked against the {@code @Filterable} allow-list: this is the
     * application's own ordering, not caller input, and the column that makes a sort total is
     * typically a surrogate key no client may filter on.
     */
    private List<OrderByTerm> defaultOrderBy(Class<?> entityType, FilterPolicy override) {
        if (override == null || override.defaultOrderBy().isBlank()) {
            return List.of();
        }
        List<OrderByTerm> terms = orderByParser.parse(override.defaultOrderBy());
        for (OrderByTerm term : terms) {
            if (resolveMappedPath(entityType, term.propertyPath()).isEmpty()) {
                throw new IllegalStateException(String.format(
                        "@FilterPolicy(defaultOrderBy = \"%s\") on %s names '%s', which is not a field of %s",
                        override.defaultOrderBy(), entityType.getSimpleName(), term.propertyPath(),
                        entityType.getSimpleName()));
            }
        }
        return terms;
    }

    /** Resolves a {@code /}-separated path of Java field names, association hops included. */
    private static Optional<Field> resolveMappedPath(Class<?> type, String propertyPath) {
        Class<?> current = type;
        Field resolved = null;
        for (String segment : propertyPath.split("/")) {
            resolved = null;
            for (Field field : allFields(current)) {
                if (field.getName().equals(segment)) {
                    resolved = field;
                    break;
                }
            }
            if (resolved == null) {
                return Optional.empty();
            }
            current = resolved.getType();
        }
        return Optional.ofNullable(resolved);
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
