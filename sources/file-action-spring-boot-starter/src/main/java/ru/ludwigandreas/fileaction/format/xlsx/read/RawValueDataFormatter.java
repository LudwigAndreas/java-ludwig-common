package ru.ludwigandreas.fileaction.format.xlsx.read;

import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

/**
 * A {@link DataFormatter} that hands back the raw number instead of a formatted string, and records
 * whether the cell's display format was a date format.
 *
 * <h2>Why the formatted value is the wrong thing to read</h2>
 *
 * <p>POI's SAX handler asks a {@code DataFormatter} to turn each numeric cell into text, and the default
 * one applies the cell's display format. That is right for showing a workbook and wrong for importing one:
 * a cell holding the number 46054 with a date format displays as {@code 01.02.2026}, and the layer above
 * then has to parse that string back into a date, guessing from a locale whether it is the first of
 * February or the second of January. The file was never ambiguous. The ambiguity is created entirely by
 * formatting the number and parsing it again.
 *
 * <p>The same loss applies to plain numbers: a price displayed to two decimals as {@code 1 234,56} has had
 * its grouping separator inserted, its decimal separator localised and any further precision rounded away,
 * and all three have to be undone by guesswork.
 *
 * <p>So this formatter returns {@link BigDecimal#toPlainString()} of the raw value, and
 * {@link #wasDateFormatted()} reports what the display format was, which is the only thing in OOXML that
 * distinguishes a date from a number. The reader reads both and builds a
 * {@link ru.ludwigandreas.fileaction.format.CellValue} carrying the number and the date-ness, leaving the
 * decision about what type the cell should be to the coercion layer, which knows the target type.
 *
 * <h2>This instance is stateful and single-threaded by design</h2>
 *
 * <p>{@link #wasDateFormatted()} reports on the most recent call. POI's handler calls the formatter and
 * then immediately hands the result to the sheet handler on the same thread, so the flag is read before
 * anything can overwrite it. One instance per reader, never shared, never a bean - which is stated here
 * because a formatter looks exactly like something that should be a singleton.
 */
final class RawValueDataFormatter extends DataFormatter {

    private static final long serialVersionUID = 1L;

    private boolean dateFormatted;

    @Override
    public String formatRawCellContents(double value, int formatIndex, String formatString,
                                        boolean use1904Windowing) {
        dateFormatted = DateUtil.isADateFormat(formatIndex, formatString);
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Whether the cell most recently formatted had a date or time display format.
     *
     * @return true when it did
     */
    boolean wasDateFormatted() {
        return dateFormatted;
    }

    /** Clears the flag before a cell that may not be numeric at all. */
    void reset() {
        dateFormatted = false;
    }
}
