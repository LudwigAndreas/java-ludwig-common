package ru.ludwigandreas.fileaction.format;

import java.math.BigDecimal;

/**
 * One cell, as the reader found it, before anything has decided what type it should be.
 *
 * <h2>Why this is not just a String</h2>
 *
 * <p>A spreadsheet cell holding a date holds a <em>number</em> - days since an epoch - and the date the
 * user sees is that number through a display format. A reader that hands the layer above a formatted
 * string has thrown the number away, and the layer above must then parse the string back, in some
 * locale, guessing whether {@code 01.02.2026} is the first of February or the second of January. The
 * answer is already unambiguous in the file and the ambiguity is created entirely by passing it as text.
 *
 * <p>The same applies to numbers: {@code 1234.56} formatted for display in a Russian locale is
 * {@code 1 234,56}, and round-tripping that through a string costs the grouping separator, the
 * non-breaking space Excel uses for it, and any trailing precision the display format rounded away.
 *
 * <p>So a cell carries whatever the file actually had. {@link #number()} is present when the cell was
 * numeric, and {@link #dateLike()} says the cell's display format was a date format, which is the only
 * thing that distinguishes a date from a plain number in OOXML. A CSV has none of this and produces
 * text-only cells, which is why the coercion layer must still be able to parse text in the caller's
 * locale - that path is for CSV and for the occasional spreadsheet column a user typed as text.
 *
 * @param text     the cell as text: the raw field for a CSV, the unformatted string value for a
 *                 workbook's inline or shared string. Null for an empty cell
 * @param number   the numeric value when the cell was numeric, else null
 * @param dateLike whether the cell's display format is a date or time format. Only meaningful with
 *                 {@link #number()} present
 */
public record CellValue(String text, BigDecimal number, boolean dateLike) {

    private static final CellValue EMPTY = new CellValue(null, null, false);

    /** An absent or blank cell. */
    public static CellValue empty() {
        return EMPTY;
    }

    /**
     * A cell the reader only has text for - every CSV field, and a workbook's string cells.
     *
     * @param text the text, which may be null or blank
     * @return the cell
     */
    public static CellValue ofText(String text) {
        return text == null || text.isBlank() ? EMPTY : new CellValue(text, null, false);
    }

    /**
     * A numeric cell.
     *
     * @param number   the value as the file holds it
     * @param dateLike whether its display format is a date format
     * @return the cell
     */
    public static CellValue ofNumber(BigDecimal number, boolean dateLike) {
        return number == null ? EMPTY : new CellValue(number.toPlainString(), number, dateLike);
    }

    /**
     * A boolean cell. Rendered as text, because the target type decides how to read it and the two
     * spellings a spreadsheet uses are not the two Java uses.
     *
     * @param value the value
     * @return the cell
     */
    public static CellValue ofBoolean(boolean value) {
        return new CellValue(Boolean.toString(value), null, false);
    }

    /** Whether there is nothing in this cell. */
    public boolean isEmpty() {
        return (text == null || text.isBlank()) && number == null;
    }

    /** The cell's text with surrounding whitespace removed, or null when empty. */
    public String trimmedText() {
        return text == null ? null : text.strip();
    }
}
