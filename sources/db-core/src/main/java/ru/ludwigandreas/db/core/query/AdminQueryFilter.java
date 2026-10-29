package ru.ludwigandreas.db.core.query;

import com.querydsl.core.types.Predicate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an administrative listing's OData {@code $filter} into a QueryDSL predicate over one entity.
 *
 * <p>A neutral seam: an administrative service depends on this type only, never on the OData starter, so a service
 * without that starter still loads every class it has. Where the starter is present the expression is parsed by it
 * - its {@code @Filterable} allow-list, depth and length limits and problem types apply unchanged; where it is not,
 * an empty filter is no filter and a non-empty one is refused.
 */
public interface AdminQueryFilter {

    /**
     * Parses a raw {@code $filter}.
     *
     * @param entity    the entity the listing reads, whose {@code @Filterable} fields are the allow-list
     * @param rawFilter the expression as sent, or null or blank for none
     * @return the parsed filter, {@link ParsedFilter#none()} when there was none
     */
    ParsedFilter parse(Class<?> entity, String rawFilter);

    /**
     * A parsed filter: the predicate to AND into the listing's query, and what it named - never its values.
     *
     * @param predicate the predicate, or null for no filter
     * @param paths     the field paths the filter named
     * @param operators each path with an operator applied to it, e.g. {@code "status eq"}
     */
    record ParsedFilter(Predicate predicate, Set<String> paths, Set<String> operators) {

        private static final ParsedFilter NONE = new ParsedFilter(null, Set.of(), Set.of());

        /** Normalises the sets. */
        public ParsedFilter {
            paths = paths == null ? Set.of() : Set.copyOf(paths);
            operators = operators == null ? Set.of() : Set.copyOf(operators);
        }

        /**
         * No filter.
         *
         * @return the empty filter
         */
        public static ParsedFilter none() {
            return NONE;
        }

        /**
         * The predicate, if the filter had one.
         *
         * @return it, or empty
         */
        public Optional<Predicate> where() {
            return Optional.ofNullable(predicate);
        }

        /**
         * Whether the filter names a field, e.g. to tell whether it bounds a time range itself.
         *
         * @param path the field path
         * @return whether the filter refers to it
         */
        public boolean constrains(String path) {
            return paths.contains(path);
        }

        /**
         * What an audit event may record about the filter: its paths and operators, sorted, never its values.
         *
         * @return the facts, empty when there was no filter
         */
        public Map<String, Object> auditFacts() {
            Map<String, Object> facts = new LinkedHashMap<>();
            if (!paths.isEmpty()) {
                facts.put("filterPaths", paths.stream().sorted().toList());
                facts.put("filterOperators", operators.stream().sorted().toList());
            }
            return facts;
        }
    }
}
