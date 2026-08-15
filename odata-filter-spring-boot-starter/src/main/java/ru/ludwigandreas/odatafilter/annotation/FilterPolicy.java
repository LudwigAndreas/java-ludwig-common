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
}
