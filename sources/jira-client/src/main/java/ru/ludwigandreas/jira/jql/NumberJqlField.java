package ru.ludwigandreas.jira.jql;

import java.util.Arrays;
import java.util.List;

/** A JQL field holding a number: a number custom field, {@code votes}, {@code watchers}, {@code workRatio}. */
public final class NumberJqlField extends JqlField {

    /**
     * Creates a reference to a field of this kind.
     *
     * @param name the field's JQL name
     */
    public NumberJqlField(String name) {
        super(name);
    }

    /** {@code field = n}. */
    public JqlClause is(Number value) {
        return binary("=", JqlValue.of(value));
    }

    /** {@code field != n}. */
    public JqlClause isNot(Number value) {
        return binary("!=", JqlValue.of(value));
    }

    /** {@code field > n}. */
    public JqlClause greaterThan(Number value) {
        return binary(">", JqlValue.of(value));
    }

    /** {@code field >= n}. */
    public JqlClause atLeast(Number value) {
        return binary(">=", JqlValue.of(value));
    }

    /** {@code field < n}. */
    public JqlClause lessThan(Number value) {
        return binary("<", JqlValue.of(value));
    }

    /** {@code field <= n}. */
    public JqlClause atMost(Number value) {
        return binary("<=", JqlValue.of(value));
    }

    /** {@code field >= low AND field <= high}, inclusive at both ends. */
    public JqlClause between(Number low, Number high) {
        return atLeast(low).and(atMost(high));
    }

    /** {@code field IN (a, b, ...)}. */
    public JqlClause in(Number... values) {
        return list("IN", Arrays.stream(values).map(JqlValue::of).toList());
    }

    /** {@code field NOT IN (a, b, ...)}. */
    public JqlClause notIn(List<Number> values) {
        return list("NOT IN", values.stream().map(JqlValue::of).toList());
    }
}
