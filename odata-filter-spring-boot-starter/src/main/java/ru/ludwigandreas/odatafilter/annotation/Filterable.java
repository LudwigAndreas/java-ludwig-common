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
 * <h2>To-one associations</h2>
 *
 * <p>Traversal into a {@code @ManyToOne}/{@code @OneToOne} is opt-in in exactly the same way:
 * annotate the <em>association field</em> and the target entity's own {@code @Filterable} fields
 * become reachable as {@code department/name} and so on, up to the entity's configured
 * {@code maxNestedPropertyDepth} ({@link FilterPolicy}). An unannotated association is a wall.
 *
 * <p>That is deliberate and it is the whole point of the annotation on an association, because an
 * entity's {@code @Filterable} fields were chosen for <em>its</em> endpoint. Traversing every
 * mapped association automatically would mean that opening one field on {@code Department} also
 * opens it on every entity that happens to reference a department, on endpoints whose authors
 * never reviewed that decision - the filter surface of an API would then grow through edits to
 * classes it does not mention.
 *
 * <p>{@link #roles()} on the association field gates the whole subtree: a caller must satisfy the
 * association's roles <em>and</em> the nested field's own roles to filter or sort on
 * {@code department/budget}. {@link #ops()} and {@link #sortable()} are ignored there - an
 * association is a path segment, not a comparable value, so {@code department eq ...} is never a
 * legal filter.
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
     * {@code FilterPrincipalResolver}, or the request is rejected with 403. On an association
     * field, the requirement applies to every path that traverses it.
     */
    String[] roles() default {};

    /** Whether this field may also be used in {@code $orderby}. */
    boolean sortable() default true;

    /** OData property name exposed to clients; defaults to the Java field name. */
    String name() default "";
}
