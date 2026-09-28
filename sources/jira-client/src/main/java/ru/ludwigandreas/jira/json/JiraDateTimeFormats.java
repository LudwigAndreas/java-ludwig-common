package ru.ludwigandreas.jira.json;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * The two timestamp formats Jira Server speaks, and why neither of them is ISO-8601 as {@code java.time}
 * understands it.
 *
 * <p>Jira renders timestamps as {@code 2024-01-15T10:30:00.000+0300} - a numeric offset with no colon.
 * {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}, which is what Jackson's stock {@code OffsetDateTime}
 * deserializer uses, requires {@code +03:00} and rejects that string outright. Registering these formatters
 * is therefore not a nicety; without them every issue read fails on {@code created}.
 *
 * <p>{@link #parser()} accepts all three offset spellings ({@code +0300}, {@code +03:00}, {@code Z}) and an
 * optional fractional second, because the exact rendering varies between Jira versions, between core fields
 * and custom field values, and between the REST API and the webhook payloads a service may feed through the
 * same model classes.
 *
 * <p>{@link #writer()} emits exactly the one form Jira accepts on input. Sending {@code +03:00} to the
 * worklog endpoint gets a 400 with "Invalid date format" - Jira's own parser is stricter than its
 * renderer.
 */
public final class JiraDateTimeFormats {

    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH", "Z").optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter WRITER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

    private JiraDateTimeFormats() {
    }

    /** Lenient reader covering every offset spelling Jira has been observed to emit. */
    public static DateTimeFormatter parser() {
        return PARSER;
    }

    /** Strict writer producing the single form Jira's own parser accepts. */
    public static DateTimeFormatter writer() {
        return WRITER;
    }
}
