package ru.ludwigandreas.fileaction.format;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DateUtil;

/**
 * Turns one {@link CellValue} into the type a row record's component declares.
 *
 * <h2>Why the number in the file beats the text on the screen</h2>
 *
 * <p>A workbook cell carrying a date carries a number, and a cell carrying a price carries a number.
 * Whenever one is present this converts <em>it</em>, and never the displayed text - so
 * {@code 01.02.2026} is not parsed at all, it is converted from the serial 46054, and the question of
 * whether it is February or January never arises. The text path exists for CSV, which genuinely has
 * nothing but text, and for the spreadsheet column a user formatted as text.
 *
 * <h2>Why the text path is not {@code new BigDecimal(text)}</h2>
 *
 * <p>Because a user in a comma-decimal locale types {@code 1 234,56}, and because Excel's grouping
 * separator in those locales is a no-break space rather than an ordinary one. Both separators are removed
 * against the caller's own locale symbols, which is what makes the same file bind correctly for a Russian
 * and an English caller.
 */
public final class CellCoercion {

    /** Grouping separators seen in real files, beyond whatever the locale nominates. */
    private static final char NO_BREAK_SPACE = (char) 0x00A0;

    /** The narrow no-break space, which newer CLDR data uses as a grouping separator. */
    private static final char NARROW_NO_BREAK_SPACE = (char) 0x202F;

    /** The apostrophe Swiss locales group with. */
    private static final char APOSTROPHE = '\'';

    private CellCoercion() {
    }

    /**
     * Coerces one cell.
     *
     * @param cell    the cell as the reader found it
     * @param target  the type the row record's component declares
     * @param context the caller's locale and zone, and the workbook's date epoch
     * @return the outcome. A blank cell coerces to {@link Coerced#ofNothing()} rather than to a refusal,
     *         because "the user left it blank" is a value the record may legitimately accept and Bean
     *         Validation is what decides whether it may - except for a primitive target, which has no null
     */
    public static Coerced coerce(CellValue cell, Class<?> target, CoercionContext context) {
        if (cell == null || cell.isEmpty()) {
            return target.isPrimitive() ? Coerced.notCoercible() : Coerced.ofNothing();
        }
        Locale locale = context.preferences().locale();
        try {
            if (target == String.class) {
                return Coerced.of(cell.trimmedText());
            }
            if (target == Boolean.class || target == boolean.class) {
                return parseBoolean(cell.trimmedText());
            }
            if (target == UUID.class) {
                return Coerced.of(UUID.fromString(cell.trimmedText()));
            }
            if (target.isEnum()) {
                return parseEnum(cell.trimmedText(), target);
            }
            if (isTemporal(target)) {
                return parseTemporal(cell, target, context);
            }
            BigDecimal number = asNumber(cell, locale);
            if (number == null) {
                return Coerced.notCoercible();
            }
            return toNumericTarget(number, target);
        } catch (IllegalArgumentException | ArithmeticException notCoercible) {
            return Coerced.notCoercible();
        }
    }

    private static boolean isTemporal(Class<?> target) {
        return target == LocalDate.class || target == LocalDateTime.class || target == Instant.class
                || target == OffsetDateTime.class;
    }

    private static Coerced parseBoolean(String text) {
        // The spellings a spreadsheet and a human actually produce, in both locales this platform ships.
        List<String> truths = List.of("true", "1", "yes", "y", "da", "+");
        List<String> falsehoods = List.of("false", "0", "no", "n", "net", "-");
        String normalised = text.toLowerCase(Locale.ROOT);
        if (truths.contains(normalised)) {
            return Coerced.of(Boolean.TRUE);
        }
        if (falsehoods.contains(normalised)) {
            return Coerced.of(Boolean.FALSE);
        }
        return Coerced.notCoercible();
    }

    private static Coerced parseEnum(String text, Class<?> target) {
        // Upper-cased in ROOT, not in the caller's locale: an enum constant is an identifier, and
        // upper-casing an identifier in the Turkish locale turns a dotted i into one that no longer matches.
        String candidate = text.strip().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        for (Object constant : target.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(candidate)) {
                return Coerced.of(constant);
            }
        }
        return Coerced.notCoercible();
    }

    private static Coerced parseTemporal(CellValue cell, Class<?> target,
                                                  CoercionContext context) {
        LocalDateTime moment = null;
        if (cell.number() != null && cell.dateLike()) {
            // The file's own number, converted with the workbook's own epoch. No parsing, no locale, no
            // ambiguity - this is the whole reason CellValue carries the number.
            moment = DateUtil.getLocalDateTime(cell.number().doubleValue(), context.date1904());
        }
        if (moment == null) {
            moment = parseTemporalText(cell.trimmedText(), context.preferences().locale());
        }
        if (moment == null) {
            return Coerced.notCoercible();
        }
        if (target == LocalDate.class) {
            return Coerced.of(moment.toLocalDate());
        }
        if (target == LocalDateTime.class) {
            return Coerced.of(moment);
        }
        // A spreadsheet date has no zone. Interpreting it in the caller's zone is the only reading that
        // matches what they typed: the same cell means a different instant for a caller in Moscow and one
        // in London, and resolving it in the container's zone would make both wrong.
        OffsetDateTime zoned = moment.atZone(context.preferences().zone()).toOffsetDateTime();
        return Coerced.of(target == Instant.class ? zoned.toInstant() : zoned);
    }

    private static LocalDateTime parseTemporalText(String text, Locale locale) {
        if (text == null) {
            return null;
        }
        for (DateTimeFormatter format : dateTimeFormats(locale)) {
            try {
                return LocalDateTime.parse(text, format);
            } catch (DateTimeParseException notThisOne) {
                continue;
            }
        }
        for (DateTimeFormatter format : dateFormats(locale)) {
            try {
                return LocalDate.parse(text, format).atStartOfDay();
            } catch (DateTimeParseException notThisOne) {
                continue;
            }
        }
        return null;
    }

    private static List<DateTimeFormatter> dateTimeFormats(Locale locale) {
        List<DateTimeFormatter> formats = new ArrayList<>();
        formats.add(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        for (FormatStyle style : List.of(FormatStyle.SHORT, FormatStyle.MEDIUM)) {
            addLocalised(formats, locale, style, true);
        }
        return formats;
    }

    /**
     * The date patterns tried against a text cell, in order.
     *
     * <p>The caller's own locale patterns come first, because a user typing a date types their locale's
     * field order, and ISO last, because it is unambiguous for everybody and so is a safe fallback rather
     * than a guess.
     *
     * <h2>Why the year is widened</h2>
     *
     * <p>{@code FormatStyle.SHORT} for {@code en-US} is {@code M/d/yy} - a <em>two</em>-digit year - so
     * {@code 2/1/2026}, which is exactly what a user types, does not parse against it, and
     * {@code FormatStyle.MEDIUM} is {@code MMM d, yyyy}, which wants a month name. Between them the two
     * localised styles reject the single most common way a date is typed in that locale. So each localised
     * pattern is also tried with its year widened to four digits.
     *
     * <p>Discovered by a test that asserted {@code 2/1/2026} binds for an American caller and found that it
     * did not. Left in the test suite for that reason.
     */
    private static List<DateTimeFormatter> dateFormats(Locale locale) {
        List<DateTimeFormatter> formats = new ArrayList<>();
        for (FormatStyle style : List.of(FormatStyle.SHORT, FormatStyle.MEDIUM)) {
            addLocalised(formats, locale, style, false);
        }
        formats.add(DateTimeFormatter.ISO_LOCAL_DATE);
        return formats;
    }

    /**
     * Adds the locale's pattern for one style, and the same pattern with its year widened.
     *
     * @param formats  the list to add to
     * @param locale   the caller's locale
     * @param style    the style whose pattern to take
     * @param withTime whether to ask for a date-and-time pattern rather than a date-only one
     */
    private static void addLocalised(List<DateTimeFormatter> formats, Locale locale, FormatStyle style,
                                     boolean withTime) {
        String pattern;
        try {
            // Exactly one of the two styles is non-null for a date-only pattern. Passing null for both
            // throws, which an earlier version of this method did and then swallowed - so no localised
            // pattern was ever added and only the ISO fallback worked. RowCoercionTest caught it.
            pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    style, withTime ? style : null, IsoChronology.INSTANCE, locale);
        } catch (IllegalArgumentException noSuchPattern) {
            return;
        }
        formats.add(DateTimeFormatter.ofPattern(pattern, locale));
        String widened = widenYear(pattern);
        if (!widened.equals(pattern)) {
            formats.add(DateTimeFormatter.ofPattern(widened, locale));
        }
    }

    /**
     * Turns a two-digit year field in a pattern into a four-digit one, leaving everything else alone.
     *
     * <p>Only {@code yy} is widened. A single {@code y} already parses a four-digit year - it is CLDR's
     * variable-width year and is what {@code ru-RU}'s short pattern uses - and widening it would change
     * nothing. {@code yyyy} is already wide.
     */
    private static String widenYear(String pattern) {
        if (pattern.contains("yyyy") || !pattern.contains("yy")) {
            return pattern;
        }
        return pattern.replace("yy", "yyyy");
    }

    private static BigDecimal asNumber(CellValue cell, Locale locale) {
        if (cell.number() != null) {
            return cell.number();
        }
        String text = cell.trimmedText();
        if (text == null || text.isBlank()) {
            return null;
        }
        java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols.getInstance(locale);
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean grouping = c == symbols.getGroupingSeparator() || c == ' '
                    || c == NO_BREAK_SPACE || c == NARROW_NO_BREAK_SPACE || c == APOSTROPHE;
            if (grouping) {
                continue;
            }
            cleaned.append(c == symbols.getDecimalSeparator() ? '.' : c);
        }
        try {
            return new BigDecimal(cleaned.toString());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Coerced toNumericTarget(BigDecimal value, Class<?> target) {
        if (target == BigDecimal.class) {
            return Coerced.of(value);
        }
        if (target == BigInteger.class) {
            return Coerced.of(value.toBigIntegerExact());
        }
        if (target == Integer.class || target == int.class) {
            return Coerced.of(value.intValueExact());
        }
        if (target == Long.class || target == long.class) {
            return Coerced.of(value.longValueExact());
        }
        if (target == Short.class || target == short.class) {
            return Coerced.of(value.shortValueExact());
        }
        if (target == Byte.class || target == byte.class) {
            return Coerced.of(value.byteValueExact());
        }
        if (target == Double.class || target == double.class) {
            return Coerced.of(value.doubleValue());
        }
        if (target == Float.class || target == float.class) {
            return Coerced.of(value.floatValue());
        }
        // A target this module has no conversion for. Reported as a cell that cannot be coerced rather than
        // thrown, so that one unusual component type on one binding does not take an upload down - and so
        // that the message names the cell, which is what the user can act on.
        return Coerced.notCoercible();
    }
}
