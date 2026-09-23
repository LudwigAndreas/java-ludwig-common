package ru.ludwigandreas.jira.jql;

import java.util.Arrays;
import java.util.List;

/**
 * A JQL field whose values are discrete identifiers rather than free text or numbers: project, status,
 * issue type, resolution, priority, component, version, label.
 *
 * <p>Offers equality, set membership, emptiness, and the history operators. Deliberately offers no
 * {@code &gt;} or {@code ~}: Jira does define an ordering on some of these (priority, version) but it is the
 * administrator's configured order, which changes when someone reorders the list in the admin UI, and a
 * query written against it silently changes meaning. Where that ordering really is wanted,
 * {@link JqlField#operator(String, JqlValue)} still reaches it - explicitly.
 */
public class ValueJqlField extends JqlField {

    /**
     * Creates a reference to a field of this kind.
     *
     * @param name the field's JQL name
     */
    public ValueJqlField(String name) {
        super(name);
    }

    /** {@code field = value}. */
    public JqlClause is(String value) {
        return binary("=", JqlValue.of(value));
    }

    /** {@code field = <function>()}. */
    public JqlClause is(JqlFunction function) {
        return binary("=", function.asValue());
    }

    /** {@code field != value}. */
    public JqlClause isNot(String value) {
        return binary("!=", JqlValue.of(value));
    }

    /** {@code field IN (a, b, ...)}. */
    public JqlClause in(String... values) {
        return in(Arrays.asList(values));
    }

    /** {@code field IN (a, b, ...)}. */
    public JqlClause in(List<String> values) {
        return list("IN", values.stream().map(JqlValue::of).toList());
    }

    /** {@code field IN <function>()}, for the functions that return a set. */
    public JqlClause in(JqlFunction function) {
        return binary("IN", function.asValue());
    }

    /** {@code field NOT IN (a, b, ...)}. */
    public JqlClause notIn(String... values) {
        return notIn(Arrays.asList(values));
    }

    /** {@code field NOT IN (a, b, ...)}. */
    public JqlClause notIn(List<String> values) {
        return list("NOT IN", values.stream().map(JqlValue::of).toList());
    }

    /** {@code field NOT IN <function>()}. */
    public JqlClause notIn(JqlFunction function) {
        return binary("NOT IN", function.asValue());
    }

    /** {@code field WAS value} - the field held this value at some point. */
    public JqlHistoryClause was(String value) {
        return new JqlHistoryClause(name() + " WAS " + JqlValue.quote(value), List.of());
    }

    /** {@code field WAS NOT value}. */
    public JqlHistoryClause wasNot(String value) {
        return new JqlHistoryClause(name() + " WAS NOT " + JqlValue.quote(value), List.of());
    }

    /** {@code field WAS IN (a, b, ...)}. */
    public JqlHistoryClause wasIn(String... values) {
        return new JqlHistoryClause(name() + " WAS IN (" + renderList(values) + ")", List.of());
    }

    /** {@code field WAS NOT IN (a, b, ...)}. */
    public JqlHistoryClause wasNotIn(String... values) {
        return new JqlHistoryClause(name() + " WAS NOT IN (" + renderList(values) + ")", List.of());
    }

    /** {@code field CHANGED} - the field was modified at all; narrow it with the predicate methods. */
    public JqlHistoryClause changed() {
        return new JqlHistoryClause(name() + " CHANGED", List.of());
    }

    /** {@code field CHANGED TO value}. */
    public JqlHistoryClause changedTo(String value) {
        return changed().to(value);
    }

    /** {@code field CHANGED FROM value}. */
    public JqlHistoryClause changedFrom(String value) {
        return changed().from(value);
    }

    private static String renderList(String... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("A JQL WAS IN list needs at least one value");
        }
        return Arrays.stream(values).map(JqlValue::quote).reduce((a, b) -> a + ", " + b).orElseThrow();
    }
}
