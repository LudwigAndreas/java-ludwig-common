package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import java.util.Set;

/**
 * How one {@link ScopeDimension} of one resource is enforced, on both sides.
 *
 * <p>Two methods, and having both on every binding is the point. The first drives pre-filtering, which
 * is what keeps a paged list endpoint honest; the second drives the post-check that catches the code
 * paths pre-filtering never sees - a {@code findById}, an entity reached through a relation, an object
 * rebuilt from an event. One without the other leaves a hole, so a binding cannot be declared with only
 * half of itself.
 *
 * <p>Three shapes, in increasing order of power and decreasing order of how much the compiler can check
 * for you:
 *
 * <ul>
 *   <li>{@link PathScopeBinding} - one column equals one of the allowed values. The common case, and
 *       fully compile-time bound: a generated Q-type path plus a method reference.</li>
 *   <li>{@link CollectionScopeBinding} - the row is related to <em>several</em> values and matches if
 *       any of them is allowed ("orders this user is mentioned on"). Still a generated path and a
 *       method reference, just many-valued on both sides.</li>
 *   <li>{@link ExpressionScopeBinding} - you supply the predicate and the check yourself, for rules
 *       that are not reachable as a single path at all. Maximum power, and the one shape where nothing
 *       verifies that your two halves agree with each other.</li>
 * </ul>
 */
public sealed interface ScopeBinding<T>
        permits PathScopeBinding, CollectionScopeBinding, ExpressionScopeBinding {

    /**
     * The query-time half.
     *
     * @return the predicate restricting rows to the allowed values, or {@code null} when this binding
     *         can never match them - which the caller must treat as "matches nothing", never as
     *         "no restriction"
     */
    BooleanExpression matches(Set<String> rawValues);

    /**
     * The load-time half: does this already-materialized object satisfy the same restriction?
     *
     * <p>Must return {@code false} for a {@code null} target and must not throw: it runs on the request
     * path, and an exception here would turn a denial into a 500.
     */
    boolean matches(T target, Set<String> rawValues);

    /** Human-readable description for configuration errors and audit records. */
    String describe();
}
