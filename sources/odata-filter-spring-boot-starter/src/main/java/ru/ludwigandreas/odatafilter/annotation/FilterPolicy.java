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

    /**
     * The name this entity's filter policy is published under, or empty to publish nothing.
     *
     * <h2>One member carrying two things, on purpose</h2>
     *
     * <p>Setting it is both the decision to publish and the identifier published under, because the two
     * are the same decision: there is no reason to name an entity that is not exposed, and no way to
     * expose one without naming it. Empty - the default - means this entity has no metadata document and
     * the endpoint answers 404 for it, exactly as it does for a name nobody has used.
     *
     * <h2>Why it is not derived from the class name</h2>
     *
     * <p>Deriving it (strip a conventional {@code Entity} suffix, uncapitalize) would publish
     * {@code ProductEntity} as {@code product} and {@code NotificationDeliveryEntity} as
     * {@code notificationDelivery}, and would make renaming a Java class a breaking change to a URL. The
     * published identifier is an API decision somebody should review; a class name is not reviewed as
     * one. {@link Filterable#name()} already works this way for the same reason.
     *
     * <h2>What publishing commits you to</h2>
     *
     * <p>The document lists the property paths a caller may name, their types, the operators permitted on
     * each and the limits enforced - all projected from this policy and the entity's {@link Filterable}
     * annotations, never from a second list. So the annotations become a published contract: renaming a
     * {@code @Filterable(name = ...)} changes the document. That was already true the moment a client sent
     * a filter naming it; publishing only makes it visible to whoever edits the annotation.
     *
     * <p>It is still filtered per caller - a path the requester may not use is absent from its document -
     * so publishing a name does not publish the whole policy to everyone.
     */
    String metadataName() default "";
}
