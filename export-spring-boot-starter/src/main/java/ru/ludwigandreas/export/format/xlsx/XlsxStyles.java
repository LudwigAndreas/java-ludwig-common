package ru.ludwigandreas.export.format.xlsx;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Workbook;
import ru.ludwigandreas.export.api.CellFormat;

/**
 * One cell style per distinct look, created once and reused for every cell that wants it.
 *
 * <h2>This class is the difference between a working exporter and one that dies in production</h2>
 *
 * <p>A workbook may contain about 64,000 cell styles. An exporter that calls
 * {@code createCellStyle()} per cell reaches that ceiling somewhere around row 10,000 of a
 * four-column report and then throws - and it does so only on real data, because the developer's
 * fixture had fifty rows. It is the single most common way an XLSX exporter fails after being
 * declared finished.
 *
 * <p>The fix is not a convention. Styles are reachable only through {@link #styleFor}, which is a
 * cache lookup keyed by everything that can make two cells look different: the column's
 * {@link CellFormat}, the emphasis, and - for a money column whose currency travels on the value -
 * the currency. The number of distinct styles is therefore bounded by the number of columns times
 * three times the number of currencies in the report, which for any real definition is dozens. A
 * writer cannot accidentally create a style per cell, because there is no method here that would.
 *
 * <p>The cost of that bound is stated on {@code CellFormat.moneyPerRow}: a per-row currency column
 * costs one style per currency rather than one for the column. That is a real cost and it is small;
 * a report spanning more than a handful of currencies is unusual, and a report spanning 64,000 of
 * them does not exist.
 *
 * <h2>Date formats</h2>
 *
 * <p>The defaults are ISO-shaped - {@code yyyy-mm-dd}, {@code yyyy-mm-dd hh:mm:ss} - rather than
 * derived from the run's locale. A date cell in XLSX is a real date, so the recipient can reformat
 * the column however they like in one click; what they cannot recover from is ambiguity about
 * whether {@code 03/04} was March or April. A column that wants something else says so with
 * {@code CellFormat.withPattern}, in Excel's own format language.
 */
final class XlsxStyles {

    /** Excel's format for an elapsed time: the brackets are what stop it wrapping at 24 hours. */
    static final String DURATION_PATTERN = "[h]:mm:ss";

    static final String DATE_PATTERN = "yyyy-mm-dd";
    static final String DATE_TIME_PATTERN = "yyyy-mm-dd hh:mm:ss";
    static final String TEXT_PATTERN = "@";

    /** Points, in POI's units of a twentieth of a point, for a column width in characters. */
    private static final int WIDTH_UNITS_PER_CHARACTER = 256;

    /** How a cell is emphasised, independently of what it contains. */
    enum Emphasis {

        /** An ordinary data cell. */
        NORMAL,

        /** A column heading: bold, filled, bordered underneath. */
        HEADER,

        /** A totals cell: bold, bordered above, and still carrying its column's number format. */
        TOTAL
    }

    private final Workbook workbook;
    private final DataFormat dataFormat;
    private final Font normalFont;
    private final Font boldFont;
    private final Map<Key, CellStyle> styles = new HashMap<>();

    XlsxStyles(Workbook workbook) {
        this.workbook = workbook;
        this.dataFormat = workbook.createDataFormat();
        this.normalFont = workbook.createFont();
        this.boldFont = workbook.createFont();
        this.boldFont.setBold(true);
    }

    /**
     * The style for a cell.
     *
     * @param format    the column's declared presentation
     * @param emphasis  how the cell is emphasised
     * @param currency  the value's own currency for a per-row money column, otherwise null
     * @return a cached style; never a new one for a look that already exists
     */
    CellStyle styleFor(CellFormat format, Emphasis emphasis, Currency currency) {
        Currency effective = format.kind() == CellFormat.Kind.MONEY && format.currency() == null
                ? currency
                : null;
        return styles.computeIfAbsent(new Key(format, emphasis, effective), this::create);
    }

    /** How many distinct styles have been created; asserted by the tests that guard the ceiling. */
    int size() {
        return styles.size();
    }

    /** A column width in POI's units. */
    static int widthOf(int characters) {
        return characters * WIDTH_UNITS_PER_CHARACTER;
    }

    private CellStyle create(Key key) {
        CellStyle style = workbook.createCellStyle();
        style.setFont(key.emphasis() == Emphasis.NORMAL ? normalFont : boldFont);
        String pattern = patternFor(key.format(), key.currency());
        if (pattern != null) {
            style.setDataFormat(dataFormat.getFormat(pattern));
        }
        if (key.emphasis() == Emphasis.HEADER) {
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            style.setAlignment(HorizontalAlignment.LEFT);
        } else if (key.emphasis() == Emphasis.TOTAL) {
            style.setBorderTop(BorderStyle.THIN);
        }
        return style;
    }

    private String patternFor(CellFormat format, Currency currency) {
        if (format.pattern() != null) {
            return format.pattern();
        }
        return switch (format.kind()) {
            case TEXT -> TEXT_PATTERN;
            case NUMBER -> decimalPattern(format.scale(), null);
            case PERCENT -> decimalPattern(format.scale(), null) + "%";
            case MONEY -> decimalPattern(format.scale(), currencyOf(format, currency));
            case DATE -> DATE_PATTERN;
            case DATETIME -> DATE_TIME_PATTERN;
            case DURATION -> DURATION_PATTERN;
            // A boolean cell is a real boolean and Excel renders it as TRUE/FALSE without help; a
            // number format here would turn it into text.
            case BOOLEAN -> null;
        };
    }

    private String currencyOf(CellFormat format, Currency perRow) {
        Currency currency = format.currency() == null ? perRow : format.currency();
        return currency == null ? null : currency.getCurrencyCode();
    }

    private String decimalPattern(int scale, String currencyCode) {
        StringBuilder pattern = new StringBuilder("#,##0");
        if (scale > 0) {
            pattern.append('.');
            pattern.append("0".repeat(scale));
        }
        if (currencyCode != null) {
            // Quoted so Excel treats the code as literal text rather than as format characters -
            // an unquoted "DKK" would be read as a date format and render the amount as a day name.
            pattern.append("\" ").append(currencyCode).append('"');
        }
        return pattern.toString();
    }

    /**
     * Everything that can make two cells look different.
     *
     * @param format   the column's presentation
     * @param emphasis header, total or ordinary
     * @param currency non-null only for a money column whose currency travels on the value, which is
     *                 the one case where two cells of the same column need different styles
     */
    private record Key(CellFormat format, Emphasis emphasis, Currency currency) {
    }
}
