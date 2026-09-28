package ru.ludwigandreas.jira.jql;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A JQL field holding a date or timestamp: created, updated, resolved, due, lastViewed, or a date custom
 * field.
 *
 * <p>Three ways to say when, and they are not interchangeable:
 *
 * <ul>
 *   <li>A {@link LocalDate} or {@link OffsetDateTime}, rendered as an absolute literal. Jira evaluates it in
 *       the <em>calling user's</em> time zone, so the same saved query run by two people in different zones
 *       returns different issues.</li>
 *   <li>A relative offset through {@link #after(String)} and friends - {@code "-7d"}, {@code "-4w"} - which
 *       Jira evaluates against the server clock. This is the form that belongs in a scheduled job.</li>
 *   <li>A function such as {@code startOfWeek()}, which respects the instance's configured week start.</li>
 * </ul>
 */
public final class DateJqlField extends JqlField {

    /**
     * Creates a reference to a field of this kind.
     *
     * @param name the field's JQL name
     */
    public DateJqlField(String name) {
        super(name);
    }

    /** {@code field >= date}. */
    public JqlClause onOrAfter(LocalDate date) {
        return binary(">=", JqlValue.of(date));
    }

    /** {@code field > date}. */
    public JqlClause after(LocalDate date) {
        return binary(">", JqlValue.of(date));
    }

    /** {@code field > timestamp}. */
    public JqlClause after(OffsetDateTime timestamp) {
        return binary(">", JqlValue.of(timestamp));
    }

    /**
     * {@code field > "<offset>"} for a relative offset such as {@code "-7d"}, {@code "-4w"}, {@code "-1M"}.
     *
     * @param relative a Jira relative-date expression
     * @return the clause
     */
    public JqlClause after(String relative) {
        return binary(">", JqlValue.of(relative));
    }

    /** {@code field > <function>()}, for {@code startOfWeek()} and friends. */
    public JqlClause after(JqlFunction function) {
        return binary(">", function.asValue());
    }

    /** {@code field <= date}. */
    public JqlClause onOrBefore(LocalDate date) {
        return binary("<=", JqlValue.of(date));
    }

    /** {@code field < date}. */
    public JqlClause before(LocalDate date) {
        return binary("<", JqlValue.of(date));
    }

    /** {@code field < timestamp}. */
    public JqlClause before(OffsetDateTime timestamp) {
        return binary("<", JqlValue.of(timestamp));
    }

    /** {@code field < "<offset>"} for a relative offset. */
    public JqlClause before(String relative) {
        return binary("<", JqlValue.of(relative));
    }

    /** {@code field < <function>()}. */
    public JqlClause before(JqlFunction function) {
        return binary("<", function.asValue());
    }

    /** {@code field = date} - Jira widens a bare date to the whole day. */
    public JqlClause on(LocalDate date) {
        return binary("=", JqlValue.of(date));
    }

    /** {@code field >= from AND field <= to}, inclusive at both ends. */
    public JqlClause between(LocalDate from, LocalDate to) {
        return onOrAfter(from).and(onOrBefore(to));
    }

    /** {@code field >= from AND field <= to} for relative offsets, for example {@code "-14d"} to {@code "-7d"}. */
    public JqlClause between(String fromRelative, String toRelative) {
        return binary(">=", JqlValue.of(fromRelative)).and(binary("<=", JqlValue.of(toRelative)));
    }
}
