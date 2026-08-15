package ru.ludwigandreas.db.core.util;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Null-safe QueryDSL {@link BooleanExpression} combinators, meant for building optional filter predicates
 * (e.g. from query parameters that may or may not be present) without hand-rolled null checks.
 */
public final class Predicates {

    private Predicates() {
    }

    /**
     * ANDs together the non-null expressions. Never returns {@code null} (falls back to an always-true
     * expression), so the result is always safe to pass into {@code findAll(predicate)}.
     */
    public static BooleanExpression allOf(BooleanExpression... expressions) {
        return Arrays.stream(expressions)
                .filter(java.util.Objects::nonNull)
                .reduce(BooleanExpression::and)
                .orElseGet(() -> Expressions.asBoolean(true).isTrue());
    }

    /**
     * Returns {@code fn.apply(value)} if {@code value} is non-null, otherwise {@code null} (to be filtered
     * out by {@link #allOf(BooleanExpression...)}).
     */
    public static <V> BooleanExpression whenNotNull(V value, Function<V, BooleanExpression> fn) {
        return value == null ? null : fn.apply(value);
    }
}
