package ru.ludwigandreas.export.format.csv;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.export.exception.InvalidFormatOptionException;
import ru.ludwigandreas.export.format.CellTextFormatter;

/**
 * The shape of one CSV file: delimiter, charset, byte-order mark, line ending and value style.
 *
 * <h2>Two profiles, because the two audiences want opposite files</h2>
 *
 * <p>There is no CSV that satisfies both readers of a report, and pretending otherwise is how an
 * export module ends up with a support queue.
 *
 * <p><b>{@code excel}</b> is UTF-8 <em>with</em> a byte-order mark, a delimiter taken from the
 * locale's list separator, CRLF line endings and locale-formatted values. Every one of those is
 * there because of something Excel does. Without the mark, Excel on Windows opens a UTF-8 file as
 * the system code page and a Cyrillic report arrives as mojibake. With a comma delimiter, Excel in a
 * locale whose decimal separator is a comma puts the whole row in column A - which is why the
 * delimiter follows the locale rather than the name of the format. The mark and the delimiter
 * together are the difference between a file a person can use and one they file a ticket about.
 *
 * <p><b>{@code rfc4180}</b> is UTF-8 with no mark, a comma, CRLF and canonical values. A byte-order
 * mark is the single most common reason a parser reads the first column's header as a mark followed
 * by {@code id} rather than as {@code id}; a grouping separator inside a number is the second most
 * common reason it reads a number as text.
 *
 * <p>{@code excel} is the default because a human opening the file is the common case for a report,
 * and a machine consumer is in a position to ask for the other.
 *
 * @param delimiter what separates fields
 * @param charset   how the text is encoded
 * @param byteOrderMark whether the file starts with a UTF-8 mark
 * @param style     which reading the values themselves use
 */
public record CsvProfile(char delimiter, Charset charset, boolean byteOrderMark,
                         CellTextFormatter.Style style) {

    /** The profile name for a file a person opens in a spreadsheet. */
    public static final String EXCEL = "excel";

    /** The profile name for a file a parser reads. */
    public static final String RFC_4180 = "rfc4180";

    /** Option names a request may carry for this format. */
    public static final Set<String> OPTIONS = Set.of("profile", "delimiter", "charset", "bom");

    /**
     * Line ending, fixed at CRLF for both profiles.
     *
     * <p>RFC 4180 requires it, and Excel on Windows wants it; a bare newline is the thing that
     * works everywhere except in the two places a CSV report actually goes.
     */
    public static final String LINE_ENDING = "\r\n";

    /**
     * The UTF-8 byte-order mark.
     *
     * <p>Built from its code point rather than written as a character literal, because a
     * {@code \}{@code uFEFF} escape is processed by the compiler before it ever reaches a string -
     * including inside a comment - and a zero-width character sitting invisibly in this file would
     * be the least debuggable thing in the module.
     */
    public static final char BOM = (char) 0xFEFF;

    private static final char COMMA = ',';
    private static final char SEMICOLON = ';';

    /**
     * Resolves the profile a run writes with.
     *
     * @param options       the request's validated per-format options
     * @param defaultProfile the configured default profile name
     * @param allowOverride whether a request may override the configured profile at all
     * @param locale        the run's locale, which decides the {@code excel} delimiter
     * @return the resolved profile
     * @throws InvalidFormatOptionException if an option's value is not usable
     */
    public static CsvProfile resolve(Map<String, String> options, String defaultProfile,
                                     boolean allowOverride, Locale locale) {
        String requested = options.get("profile");
        if (requested != null && !allowOverride) {
            throw new InvalidFormatOptionException("profile", requested);
        }
        String name = requested == null ? defaultProfile : requested;
        CsvProfile base = baseProfile(name, locale);
        return base
                .withDelimiter(options.get("delimiter"))
                .withCharset(options.get("charset"))
                .withByteOrderMark(options.get("bom"));
    }

    /** The unmodified shape of a named profile. */
    public static CsvProfile baseProfile(String name, Locale locale) {
        if (RFC_4180.equalsIgnoreCase(name)) {
            return new CsvProfile(COMMA, StandardCharsets.UTF_8, false, CellTextFormatter.Style.CANONICAL);
        }
        if (!EXCEL.equalsIgnoreCase(name)) {
            throw new InvalidFormatOptionException("profile", name);
        }
        return new CsvProfile(listSeparator(locale), StandardCharsets.UTF_8, true,
                CellTextFormatter.Style.LOCALIZED);
    }

    /**
     * The separator a spreadsheet in this locale expects.
     *
     * <p>Derived from the locale's decimal separator rather than from a table of countries: a locale
     * whose numbers are written {@code 1 234,56} cannot use a comma to separate fields, and that is
     * precisely the rule the spreadsheet itself applies. Russian and German get a semicolon; English
     * gets a comma; a locale nobody thought about gets the right answer without an entry here.
     */
    public static char listSeparator(Locale locale) {
        return DecimalFormatSymbols.getInstance(locale).getDecimalSeparator() == COMMA ? SEMICOLON : COMMA;
    }

    private CsvProfile withDelimiter(String option) {
        if (option == null) {
            return this;
        }
        if (option.length() != 1) {
            throw new InvalidFormatOptionException("delimiter", option);
        }
        char candidate = option.charAt(0);
        if (candidate == '"' || candidate == '\r' || candidate == '\n') {
            // A delimiter that is also the quote character or a line ending makes the file
            // unparseable by anything, including this module's own tests.
            throw new InvalidFormatOptionException("delimiter", option);
        }
        return new CsvProfile(candidate, charset, byteOrderMark, style);
    }

    private CsvProfile withCharset(String option) {
        if (option == null) {
            return this;
        }
        try {
            return new CsvProfile(delimiter, Charset.forName(option), byteOrderMark, style);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new InvalidFormatOptionException("charset", option);
        }
    }

    private CsvProfile withByteOrderMark(String option) {
        if (option == null) {
            return this;
        }
        if (!"true".equalsIgnoreCase(option) && !"false".equalsIgnoreCase(option)) {
            throw new InvalidFormatOptionException("bom", option);
        }
        return new CsvProfile(delimiter, charset, Boolean.parseBoolean(option), style);
    }

    /**
     * Whether a mark is actually written.
     *
     * <p>A byte-order mark is only meaningful for UTF-8 here: written in front of a Windows-1251
     * file - which an estate with an older consumer may well ask for - it would be three stray
     * characters at the start of the first header, which is the very failure the mark exists to
     * prevent in the other direction.
     */
    public boolean writesByteOrderMark() {
        return byteOrderMark && StandardCharsets.UTF_8.equals(charset);
    }
}
