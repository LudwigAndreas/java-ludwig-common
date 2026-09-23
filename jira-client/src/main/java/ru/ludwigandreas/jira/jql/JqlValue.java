package ru.ludwigandreas.jira.jql;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One literal or function call on the right-hand side of a JQL clause, and the escaping that makes it safe.
 *
 * <p>Escaping is the whole reason this type exists. JQL is a query language with reserved words, quoting
 * rules and backslash escapes, and a client that builds it by string concatenation has a JQL injection bug
 * the first time an issue summary contains a quotation mark - at which point a caller searching for
 * {@code summary ~ "it's 5" tall"} either gets a parse error or, with an attacker-supplied value, a query
 * that reads issues they may not see.
 *
 * <p>The rules implemented here are Jira's: a string is wrapped in double quotes; {@code \} and {@code "}
 * inside it are doubled with a backslash; and the four whitespace escapes JQL recognizes
 * ({@code \n}, {@code \r}, {@code \t}, and the form feed) are emitted in their escaped form because a raw
 * newline inside a quoted JQL string is a parse error.
 */
public final class JqlValue {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final String rendered;

    private JqlValue(String rendered) {
        this.rendered = rendered;
    }

    /** A quoted, escaped string literal. */
    public static JqlValue of(String text) {
        return new JqlValue(quote(text));
    }

    /** A numeric literal, which JQL takes unquoted. */
    public static JqlValue of(Number number) {
        return new JqlValue(String.valueOf(number));
    }

    /** A boolean literal, rendered as a quoted string because JQL has no boolean type. */
    public static JqlValue of(boolean value) {
        return new JqlValue(quote(Boolean.toString(value)));
    }

    /** A date literal, {@code "yyyy-MM-dd"}. */
    public static JqlValue of(LocalDate date) {
        return new JqlValue(quote(date.toString()));
    }

    /**
     * A timestamp literal, {@code "yyyy-MM-dd HH:mm"}.
     *
     * <p>Seconds are dropped because JQL does not accept them, and the offset is dropped because JQL has no
     * way to express one: Jira evaluates the literal in the <em>calling user's</em> time zone, not in the
     * zone of the value passed here. A query that must be zone-stable should use a date, or a relative
     * function such as {@code -7d}.
     */
    public static JqlValue of(OffsetDateTime timestamp) {
        return new JqlValue(quote(DATE_TIME.format(timestamp)));
    }

    /** A JQL function call such as {@code currentUser()}, emitted without quotes. */
    public static JqlValue function(JqlFunction function) {
        return new JqlValue(function.render());
    }

    /**
     * Text inserted with no escaping at all.
     *
     * <p>The escape hatch for a construct this builder does not model - a relative date range like
     * {@code -4w}, a marketplace app's function. Never pass user input through it: that is exactly the
     * injection this class exists to prevent.
     *
     * @param text raw JQL fragment
     * @return the fragment, verbatim
     */
    public static JqlValue raw(String text) {
        return new JqlValue(text);
    }

    /** The {@code EMPTY} keyword, which JQL treats as "has no value". */
    public static JqlValue empty() {
        return new JqlValue("EMPTY");
    }

    /**
     * Quotes and escapes a string for use as a JQL literal.
     *
     * @param text the raw text
     * @return the text as a quoted JQL string literal
     */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** The rendered JQL fragment for this value. */
    public String render() {
        return rendered;
    }

    @Override
    public String toString() {
        return rendered;
    }
}
