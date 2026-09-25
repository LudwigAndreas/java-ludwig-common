package ru.ludwigandreas.export.api;

/**
 * What a column does when its extractor yields nothing.
 *
 * <p>Absence is declared per column rather than handled globally because the two reasonable answers
 * are genuinely different documents. A finance column left blank is read as "zero has not been
 * entered yet"; a status column left blank is read as a defect in the export. Forcing the choice
 * into the definition means the recipient is looking at what the author intended in both cases.
 *
 * <p>Note that this covers a missing <em>source</em> value. A value missing because a partner did
 * not know the key is a different situation with its own answer; see {@link MissingPolicy}.
 */
public enum NullPolicy {

    /**
     * A genuinely empty cell. The default, and the only option that leaves spreadsheet functions
     * behaving as the recipient expects - {@code AVERAGE} over a column of blanks and numbers
     * ignores the blanks, where a column of zeroes and numbers does not.
     */
    EMPTY,

    /**
     * A localized placeholder such as a dash, resolved from the module's bundle at write time.
     *
     * <p>Costs the column its numeric type in formats that have one, so it is for text and status
     * columns rather than for amounts.
     */
    PLACEHOLDER,

    /**
     * Numeric zero, or its equivalent.
     *
     * <p>Only legal on a numeric format, and never the default: writing zero for "unknown" is the
     * fastest way to produce a report whose totals are confidently wrong.
     */
    ZERO,

    /**
     * Fail the whole run, naming the column and the row's key.
     *
     * <p>For a column the definition considers structurally impossible to be absent - a primary key,
     * a status with a database-level default. Reaching it means the query and the definition have
     * drifted apart, which is worth a loud failure rather than a file with holes in it.
     */
    FAIL
}
