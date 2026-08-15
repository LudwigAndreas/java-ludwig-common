package ru.ludwigandreas.db.core.util;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PredicatesTest {

    private static final BooleanExpression A = Expressions.asBoolean(true).isTrue();
    private static final BooleanExpression B = Expressions.asBoolean(false).isFalse();

    @Test
    void allOfWithNoNonNullArgumentsReturnsAlwaysTrueExpression() {
        BooleanExpression combined = Predicates.allOf((BooleanExpression) null, null);
        assertThat(combined.toString()).isEqualTo(Expressions.asBoolean(true).isTrue().toString());
    }

    @Test
    void allOfFiltersOutNullsAndAndsTheRest() {
        BooleanExpression combined = Predicates.allOf(A, null, B);
        assertThat(combined.toString()).isEqualTo(A.and(B).toString());
    }

    @Test
    void whenNotNullAppliesFunctionOnlyForNonNullValue() {
        assertThat(Predicates.whenNotNull("x", v -> A)).isSameAs(A);
        assertThat(Predicates.<String>whenNotNull(null, v -> A)).isNull();
    }
}
