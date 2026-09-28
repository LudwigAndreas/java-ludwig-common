package ru.ludwigandreas.export.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * Renders a typed cell as text, for the formats that have no cell types.
 *
 * <h2>Two styles, because the two audiences want opposite files</h2>
 *
 * <p>This is the same split the CSV profiles make, carried down to the value. A human opening the
 * file in Excel wants {@code 1 234,56} and {@code 31.12.2025} - their locale's separators, so their
 * spreadsheet re-parses the columns as numbers and dates. A machine parsing the file wants
 * {@code 1234.56} and {@code 2025-12-31}, with no grouping separator that could be confused with a
 * delimiter and no locale it has to be told about out of band.
 *
 * <p>Neither is a better default in general, which is why the choice travels with the profile rather
 * than being settled here. What is settled here is that the file is internally consistent: one
 * style for every cell of one file.
 *
 * <h2>Money carries its currency in the value</h2>
 *
 * <p>A text format has no styling, so the currency has nowhere else to go. An amount is written as
 * the number, a space, and the ISO code. The alternative - the bare number, with the currency
 * implied by the column - is unreadable the moment a report spans markets, which is exactly the case
 * {@code CellFormat.moneyPerRow()} exists for, and a column that behaved differently depending on
 * which money format it declared would be worse than one convention applied everywhere.
 *
 * <h2>Thread safety</h2>
 *
 * <p>There is none, deliberately. {@link NumberFormat} is not thread-safe, and the alternative -
 * building one per cell - is twenty-five million allocations at the design point. The engine calls
 * a writer from one thread for the life of a run, which is what makes holding these safe; a writer
 * that introduced its own concurrency would have to hold one formatter per thread.
 */
public final class CellTextFormatter {

    /** Seconds in a day: a duration cell carries a fraction of one, and this converts it back. */
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);

    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;

    /** Which of the two readings of a value a file uses throughout. */
    public enum Style {

        /** The reader's locale: grouped numbers, locale date order, Excel's own boolean spelling. */
        LOCALIZED,

        /** ISO-8601 dates, unGrouped numbers with a dot, lowercase booleans. For a parser. */
        CANONICAL
    }

    private final CellFormat format;
    private final Style style;
    private final ExportMessages messages;
    private final Locale locale;
    private final NumberFormat numbers;
    private final DateTimeFormatter dates;
    private final DateTimeFormatter dateTimes;

    /**
     * Builds the formatter for one column of one file.
     *
     * @param format   the column's declared presentation
     * @param style    which reading this file uses
     * @param context  the run's locale and timezone
     * @param messages resolves the text of an {@link CellValue.Error} cell
     */
    public CellTextFormatter(CellFormat format, Style style, RenderContext context,
                             ExportMessages messages) {
        this.format = format;
        this.style = style;
        this.messages = messages;
        this.locale = context.locale();
        this.numbers = numberFormat(format, style, context.locale());
        this.dates = style == Style.CANONICAL
                ? DateTimeFormatter.ISO_LOCAL_DATE
                : DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(context.locale());
        this.dateTimes = style == Style.CANONICAL
                ? DateTimeFormatter.ISO_LOCAL_DATE_TIME
                : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
                        .withLocale(context.locale());
    }

    /** One formatter per column of a sheet, in the sheet's column order. */
    public static List<CellTextFormatter> forSheet(List<ColumnSpec> columns, Style style,
                                                  RenderContext context, ExportMessages messages) {
        return columns.stream()
                .map(column -> new CellTextFormatter(column.format(), style, context, messages))
                .toList();
    }

    /**
     * Renders one cell.
     *
     * @param value the typed cell
     * @return its text; empty for {@link CellValue.Empty}, never null
     */
    // A flat run of instanceof patterns rather than a switch over the sealed type: pattern matching
    // for switch is a preview feature on the Java 17 baseline this platform targets, and every branch
    // here returns immediately, so the shape is the same and the exhaustiveness is enforced by the
    // final throw - which is unreachable for as long as CellValue stays sealed over these eight.
    public String format(CellValue value) {
        if (value instanceof CellValue.Text text) {
            return text.value();
        }
        if (value instanceof CellValue.Number number) {
            return number(number.value());
        }
        if (value instanceof CellValue.Money money) {
            return numbers.format(money.amount()) + " " + money.currency().getCurrencyCode();
        }
        if (value instanceof CellValue.Date date) {
            return dates.format(date.value());
        }
        if (value instanceof CellValue.DateTime dateTime) {
            return dateTimes.format(LocalDateTime.ofInstant(dateTime.value(), dateTime.zone()));
        }
        if (value instanceof CellValue.Bool flag) {
            return bool(flag.value());
        }
        if (value instanceof CellValue.Empty) {
            return "";
        }
        if (value instanceof CellValue.Error error) {
            return messages.resolve(error.messageKey(), locale, error.args());
        }
        throw new IllegalStateException("Unhandled CellValue shape: " + value.getClass().getName());
    }

    private String number(BigDecimal value) {
        if (format.kind() == CellFormat.Kind.DURATION) {
            return duration(value);
        }
        if (format.kind() == CellFormat.Kind.PERCENT) {
            return numbers.format(value.movePointRight(2)) + "%";
        }
        return numbers.format(value);
    }

    private String bool(boolean value) {
        // Excel writes and recognizes TRUE/FALSE in a cell, so a localized file that used anything
        // else would arrive as text in a column the recipient wanted to filter on.
        return style == Style.CANONICAL ? Boolean.toString(value) : (value ? "TRUE" : "FALSE");
    }

    /**
     * A duration cell back from its stored fraction of a day.
     *
     * <p>Hours rather than days, and unbounded rather than wrapping at 24: an elapsed time of
     * thirty hours is thirty hours, and a file that showed it as six would be quietly wrong. This is
     * the text equivalent of Excel's {@code [h]:mm:ss}, whose square brackets mean the same thing.
     */
    private String duration(BigDecimal fractionOfDay) {
        long totalSeconds = fractionOfDay.multiply(SECONDS_PER_DAY)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        Duration elapsed = Duration.ofSeconds(Math.abs(totalSeconds));
        String sign = totalSeconds < 0 ? "-" : "";
        return String.format(Locale.ROOT, "%s%d:%02d:%02d", sign,
                elapsed.toHours(),
                elapsed.toMinutesPart() % MINUTES_PER_HOUR,
                elapsed.toSecondsPart() % SECONDS_PER_MINUTE);
    }

    private static NumberFormat numberFormat(CellFormat format, Style style, Locale locale) {
        int scale = scaleFor(format);
        if (style == Style.CANONICAL) {
            // A fixed, locale-independent shape: no grouping separator (which in several locales is
            // the character a CSV would otherwise use as its delimiter) and a dot for the decimal.
            DecimalFormat canonical = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ROOT));
            canonical.setGroupingUsed(false);
            canonical.setMinimumFractionDigits(scale);
            canonical.setMaximumFractionDigits(scale);
            return canonical;
        }
        NumberFormat localized = NumberFormat.getNumberInstance(locale);
        localized.setMinimumFractionDigits(scale);
        localized.setMaximumFractionDigits(scale);
        localized.setRoundingMode(RoundingMode.HALF_UP);
        return localized;
    }

    private static int scaleFor(CellFormat format) {
        if (format.kind() == CellFormat.Kind.MONEY && format.currency() != null) {
            return Math.max(0, format.currency().getDefaultFractionDigits());
        }
        return format.scale();
    }
}
