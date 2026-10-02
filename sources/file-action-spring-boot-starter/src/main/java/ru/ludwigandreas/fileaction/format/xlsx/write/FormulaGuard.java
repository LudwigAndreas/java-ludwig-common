package ru.ludwigandreas.fileaction.format.xlsx.write;

/**
 * Makes a value safe to write into a file somebody will open in a spreadsheet.
 *
 * <h2>Why a generated file needs this at all</h2>
 *
 * <p>A spreadsheet application treats a cell whose text begins with {@code =}, {@code +}, {@code -} or
 * {@code @} as a formula. So a value that came out of a user's own upload and goes back into the error
 * report they download is a formula injection: {@code =HYPERLINK("http://...","click")} in a product name
 * becomes a live link in the report, and the {@code DDE} and {@code WEBSERVICE} forms are worse. The person
 * who opens the report is usually not the person who uploaded the file, which is what makes it an attack
 * rather than a curiosity.
 *
 * <p>It applies to CSV in particular, because a CSV has no cell types at all - everything in it is text
 * that the application re-interprets on open, so the only defence is in what is written.
 *
 * <h2>Why a leading apostrophe and not an escape</h2>
 *
 * <p>There is no escape. A spreadsheet decides what a cell is from its first character, so the only way to
 * say "this is text" is to make the first character something else. A leading apostrophe is what Excel
 * itself writes for a text cell that looks like a formula, it is not displayed, and it survives a
 * round-trip. Stripping the character instead would change the user's data, which is worse: they would be
 * looking at a report that disagrees with their file.
 */
public final class FormulaGuard {

    /** The characters a spreadsheet reads as the start of a formula. */
    private static final String FORMULA_STARTERS = "=+-@";

    /**
     * Characters that a spreadsheet skips before deciding, so they can hide a formula starter behind them.
     *
     * <p>Tab, carriage return and newline are all legal leading whitespace to Excel's parser and all get
     * skipped before it looks for the {@code =}. A guard that only checked {@code charAt(0)} would pass
     * {@code "\t=cmd"} straight through.
     */
    private static final String SKIPPED_PREFIX = "\t\r\n ";

    private FormulaGuard() {
    }

    /**
     * Returns the value as it should be written.
     *
     * @param value the value, which may be null
     * @return the value prefixed so a spreadsheet reads it as text, or the value unchanged when it is
     *         already safe
     */
    public static String neutralise(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int i = 0;
        while (i < value.length() && SKIPPED_PREFIX.indexOf(value.charAt(i)) >= 0) {
            i++;
        }
        if (i >= value.length()) {
            return value;
        }
        return FORMULA_STARTERS.indexOf(value.charAt(i)) >= 0 ? "'" + value : value;
    }
}
