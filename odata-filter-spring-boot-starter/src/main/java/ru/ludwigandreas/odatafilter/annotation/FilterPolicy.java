package ru.ludwigandreas.odatafilter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional class-level override of the global {@code odata.filter.*} limits for one entity.
 * Any attribute left at its default ({@code -1}) falls back to the global
 * {@code ODataFilterProperties} value.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FilterPolicy {

    /** Maximum allowed {@link ru.ludwigandreas.odatafilter.ast.FilterNode#depth()} for this entity. */
    int maxDepth() default -1;

    /** Maximum value callers may pass as {@code $top} for this entity. */
    int maxPageSize() default -1;

    /** Page size used when {@code $top} is omitted. */
    int defaultPageSize() default -1;

    /** Maximum number of {@code /}-separated segments allowed in a property path (association traversal depth). */
    int maxNestedPropertyDepth() default -1;

    /**
     * Server-side ordering, written in {@code $orderby} syntax (e.g. {@code "createdAt desc, id asc"}),
     * appended to whatever the caller asked for.
     *
     * <p>Set it on every entity reachable through a paged endpoint, and end it on a unique column.
     * Without a total order the database is free to return rows in any order it likes, and it does
     * change its mind: two requests for {@code $skip=0} and {@code $skip=20} are two independent
     * queries, so a row can appear on both pages or on neither - a paging bug that only shows up on
     * production data volumes, and never reproduces on demand.
     *
     * <p>It is appended rather than used only as a fallback, so it breaks ties under the caller's own
     * {@code $orderby} too: {@code $orderby=status} over four distinct statuses orders nothing within
     * a status, which is the same bug with extra steps.
     *
     * <p>These paths are configuration written by the application, not caller input, so they are not
     * subject to {@link Filterable}: ordering on an unexposed surrogate key is the normal case, and
     * a tie-breaker that 403s for every caller without the role would be useless. They are still
     * checked against the entity's mapped fields when the policy is first resolved, so a typo fails
     * the first request with a clear message rather than a JPQL error.
     */
    String defaultOrderBy() default "";
}
