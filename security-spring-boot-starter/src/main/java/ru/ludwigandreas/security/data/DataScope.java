package ru.ludwigandreas.security.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The answer to "which rows of this resource may this caller touch?", in a form that can be compiled
 * either into a SQL predicate or into a yes/no check on a single object.
 *
 * <p>The shape is a disjunction of conjunctions, and that is forced by reality rather than chosen for
 * elegance: a caller usually holds several roles, each granting its own slice, and the slices have to
 * be OR-ed - a support agent who is both {@code ROLE_AGENT} (their own cases) and {@code ROLE_AUDITOR}
 * (everything in their region) must see the union, not the intersection. Within one grant the axes
 * are AND-ed, because "my own rows in my tenant" is a single narrowing statement.
 *
 * <p>So: {@code alternatives} are OR-ed; the dimensions inside one {@link Restriction} are AND-ed;
 * the values inside one dimension are OR-ed (an {@code IN} list).
 *
 * <p>{@link Access#ALL} is not the same as a restriction that happens to match everything, and
 * {@link Access#NONE} is not the same as an empty restriction list. Keeping them explicit is what
 * lets {@link DataScopePredicateFactory} emit "no WHERE clause" for one and "never matches" for the
 * other, instead of guessing from an empty collection - the classic way an unscoped query escapes.
 */
public record DataScope(Access access, List<Restriction> alternatives) {

    private static final DataScope ALL = new DataScope(Access.ALL, List.of());
    private static final DataScope NONE = new DataScope(Access.NONE, List.of());

    public enum Access {
        /** Every row. Emitted as no additional predicate at all. */
        ALL,
        /** Only rows matching at least one {@link Restriction}. */
        RESTRICTED,
        /** No row. Emitted as an always-false predicate, so a list endpoint returns an empty page
         *  rather than an error - the caller learns nothing about what exists. */
        NONE
    }

    public DataScope {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        if (access == Access.RESTRICTED && alternatives.isEmpty()) {
            throw new IllegalArgumentException("a RESTRICTED scope must carry at least one restriction");
        }
    }

    public static DataScope all() {
        return ALL;
    }

    public static DataScope none() {
        return NONE;
    }

    public static DataScope restrictedTo(Map<ScopeDimension, Set<String>> restriction) {
        Restriction single = new Restriction(restriction);
        return single.isUnsatisfiable()
                ? NONE
                : new DataScope(Access.RESTRICTED, List.of(single));
    }

    public static DataScope restrictedTo(ScopeDimension dimension, String value) {
        return value == null || value.isBlank()
                ? NONE
                : restrictedTo(Map.of(dimension, Set.of(value)));
    }

    public boolean isUnrestricted() {
        return access == Access.ALL;
    }

    public boolean denies() {
        return access == Access.NONE;
    }

    /**
     * Combines two grants of the same caller. Most permissive wins, which is the only defensible rule
     * when the grants come from independent sources (a role default from configuration, an explicit
     * grant row from the identity projection): an explicit "you may see everything" must not be
     * silently narrowed by a default that knew less.
     */
    public DataScope union(DataScope other) {
        if (other == null || other.denies()) {
            return this;
        }
        if (denies()) {
            return other;
        }
        if (isUnrestricted() || other.isUnrestricted()) {
            return ALL;
        }
        List<Restriction> merged = new ArrayList<>(alternatives);
        for (Restriction restriction : other.alternatives) {
            if (!merged.contains(restriction)) {
                merged.add(restriction);
            }
        }
        return new DataScope(Access.RESTRICTED, merged);
    }

    /**
     * One grant: every dimension in it must match ({@code AND}), and within a dimension any listed
     * value matches ({@code IN}).
     */
    public record Restriction(Map<ScopeDimension, Set<String>> values) {

        public Restriction {
            Map<ScopeDimension, Set<String>> copy = new LinkedHashMap<>();
            if (values != null) {
                values.forEach((dimension, allowed) -> copy.put(
                        dimension,
                        allowed == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(allowed))));
            }
            values = Collections.unmodifiableMap(copy);
        }

        /**
         * A dimension restricted to an empty value set can never match, so the whole conjunction is
         * dead. Callers turn this into {@link Access#NONE} rather than emitting {@code col IN ()},
         * which some dialects reject outright and others quietly treat as true.
         */
        public boolean isUnsatisfiable() {
            return values.isEmpty() || values.values().stream().anyMatch(Set::isEmpty);
        }
    }
}
