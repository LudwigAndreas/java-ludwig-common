package ru.ludwigandreas.odatafilter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA entity field as reachable through {@code $filter}/{@code $orderby}. Fields without
 * this annotation are never filterable or sortable, regardless of $filter content - this is a
 * deny-by-default allow-list, not a deny-list, so sensitive fields (password hashes, internal
 * flags, ...) are safe unless explicitly opted in.
 *
 * <p>Can also be placed on a field that holds a to-one association (e.g. {@code @ManyToOne
 * private Department department;}), in which case {@code Department}'s own {@code @Filterable}
 * fields become reachable as {@code department/name} and so on, up to the entity's configured
 * {@code maxNestedPropertyDepth} ({@link FilterPolicy}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Filterable {

    /** Operators callers may use against this field. Defaults to every operator. */
    FilterOperator[] ops() default {
            FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GE,
            FilterOperator.LT, FilterOperator.LE, FilterOperator.CONTAINS,
            FilterOperator.STARTSWITH, FilterOperator.ENDSWITH, FilterOperator.IN
    };

    /**
     * Roles allowed to filter/sort on this field. Empty (the default) means any caller the
     * application lets reach the endpoint at all - i.e. no extra restriction. A caller must
     * hold at least one of the listed roles, as reported by the active
     * {@code FilterPrincipalResolver}, or the request is rejected with 403.
     */
    String[] roles() default {};

    /** Whether this field may also be used in {@code $orderby}. */
    boolean sortable() default true;

    /** OData property name exposed to clients; defaults to the Java field name. */
    String name() default "";
}
