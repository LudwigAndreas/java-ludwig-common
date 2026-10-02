package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.format.CellCoercion;
import ru.ludwigandreas.fileaction.format.CellValue;
import ru.ludwigandreas.fileaction.format.Coerced;
import ru.ludwigandreas.fileaction.format.CoercionContext;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * Turning a cell into the type a row record wants, in the caller's locale and the workbook's epoch.
 *
 * <p>Every case here would pass or fail differently if the code read {@code Locale.getDefault()}, which is
 * why {@code RuleGroup.PRESENTATION} forbids it and why these tests pass an explicit locale rather than
 * relying on the one the build happens to run in.
 */
class RowCoercionTest {

    private static final CoercionContext RUSSIAN = new CoercionContext(
            new UserPreferences(Locale.forLanguageTag("ru-RU"), ZoneId.of("Europe/Moscow")), false);

    private static final CoercionContext AMERICAN = new CoercionContext(
            new UserPreferences(Locale.US, ZoneId.of("America/New_York")), false);

    /** The no-break space Excel uses as a grouping separator in comma-decimal locales. */
    private static final String NO_BREAK_SPACE = String.valueOf((char) 0x00A0);

    /** 2026-02-01 as a serial number under the Windows epoch. */
    private static final BigDecimal FIRST_OF_FEBRUARY_2026 = new BigDecimal("46054");

    @Test
    @DisplayName("a comma decimal separator reads as a decimal point for a Russian caller")
    void readsACommaDecimalSeparator() {
        Coerced value = coerce(CellValue.ofText("1234,56"), BigDecimal.class, RUSSIAN);

        assertThat(value.coercible()).isTrue();
        assertThat(value.value()).isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("a grouping separator is removed, including the no-break space Excel uses")
    void removesGroupingSeparators() {
        assertThat(valueOf("1" + NO_BREAK_SPACE + "234,56", BigDecimal.class, RUSSIAN))
                .isEqualTo(new BigDecimal("1234.56"));
        assertThat(valueOf("1 234,56", BigDecimal.class, RUSSIAN))
                .isEqualTo(new BigDecimal("1234.56"));
        assertThat(valueOf("1,234.56", BigDecimal.class, AMERICAN))
                .isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("the same text means different numbers in different locales, which is the whole point")
    void theSameTextIsLocaleDependent() {
        // A comma is the decimal separator in ru-RU and the grouping separator in en-US, so this one string
        // is a number near one and a number near a thousand depending on who uploaded the file. Read against
        // Locale.getDefault() one of the two is silently wrong, and which one depends on the container's
        // locale rather than on anything in the request - which is why that accessor is forbidden here.
        assertThat(valueOf("1,234", BigDecimal.class, RUSSIAN)).isEqualTo(new BigDecimal("1.234"));
        assertThat(valueOf("1,234", BigDecimal.class, AMERICAN)).isEqualTo(new BigDecimal("1234"));
    }

    @Test
    @DisplayName("a numeric cell uses the number in the file and consults no locale at all")
    void prefersTheNumberOverTheText() {
        Coerced value = coerce(CellValue.ofNumber(new BigDecimal("1234.56"), false),
                BigDecimal.class, RUSSIAN);

        assertThat(value.value()).isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("a date-formatted serial converts from the workbook's epoch, with no parsing")
    void convertsADateSerial() {
        Coerced value = coerce(CellValue.ofNumber(FIRST_OF_FEBRUARY_2026, true),
                LocalDate.class, RUSSIAN);

        assertThat(value.value()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("the same serial is four years earlier under the 1904 epoch, which is why the flag is read")
    void honoursThe1904Epoch() {
        CellValue cell = CellValue.ofNumber(FIRST_OF_FEBRUARY_2026, true);
        CoercionContext macintosh = new CoercionContext(
                new UserPreferences(Locale.US, ZoneId.of("UTC")), true);

        LocalDate windowsDate = (LocalDate) coerce(cell, LocalDate.class, AMERICAN).value();
        LocalDate macDate = (LocalDate) coerce(cell, LocalDate.class, macintosh).value();

        assertThat(macDate)
                .as("reading a Mac-authored workbook with the Windows epoch is four years and a day out, and"
                        + " nothing about the code looks wrong")
                .isNotEqualTo(windowsDate)
                .isEqualTo(windowsDate.plusYears(4).plusDays(1));
    }

    @Test
    @DisplayName("a date typed as text is read in the caller's locale order")
    void parsesADateInTheCallersLocale() {
        assertThat(valueOf("01.02.2026", LocalDate.class, RUSSIAN))
                .as("dd.MM.yyyy in ru-RU is the first of February")
                .isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(valueOf("2/1/2026", LocalDate.class, AMERICAN))
                .as("M/d/yyyy in en-US is also the first of February. The localised SHORT pattern for that"
                        + " locale has a two-digit year, so this case only passes because the patterns are"
                        + " also tried with the year widened")
                .isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("an ISO date reads for everybody")
    void parsesIso() {
        assertThat(valueOf("2026-02-01", LocalDate.class, RUSSIAN)).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(valueOf("2026-02-01", LocalDate.class, AMERICAN)).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("a spreadsheet date becomes an instant in the caller's zone, not the container's")
    void resolvesAnInstantInTheCallersZone() {
        CellValue cell = CellValue.ofNumber(FIRST_OF_FEBRUARY_2026, true);

        Instant moscow = (Instant) coerce(cell, Instant.class, RUSSIAN).value();
        Instant newYork = (Instant) coerce(cell, Instant.class, AMERICAN).value();

        assertThat(moscow)
                .as("the same cell is a different instant for callers in different zones, and resolving it"
                        + " in the container's zone would make both of them wrong")
                .isNotEqualTo(newYork)
                .isBefore(newYork);
    }

    @Test
    @DisplayName("integers, longs and doubles coerce from a numeric cell")
    void coercesNumericTargets() {
        CellValue three = CellValue.ofNumber(new BigDecimal("3"), false);

        assertThat(coerce(three, Integer.class, RUSSIAN).value()).isEqualTo(3);
        assertThat(coerce(three, Long.class, RUSSIAN).value()).isEqualTo(3L);
        assertThat(coerce(three, Double.class, RUSSIAN).value()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("a decimal that will not fit an integer is refused rather than silently truncated")
    void refusesLossyIntegerCoercion() {
        Coerced value = coerce(CellValue.ofNumber(new BigDecimal("3.7"), false), Integer.class, RUSSIAN);

        assertThat(value.coercible())
                .as("importing a quantity of 3 for a cell that says 3.7 is a silently wrong order")
                .isFalse();
    }

    @Test
    @DisplayName("booleans read in the spellings users actually produce")
    void coercesBooleans() {
        assertThat(valueOf("Yes", Boolean.class, RUSSIAN)).isEqualTo(Boolean.TRUE);
        assertThat(valueOf("1", Boolean.class, RUSSIAN)).isEqualTo(Boolean.TRUE);
        assertThat(valueOf("no", Boolean.class, RUSSIAN)).isEqualTo(Boolean.FALSE);
        assertThat(coerce(CellValue.ofText("maybe"), Boolean.class, RUSSIAN).coercible()).isFalse();
    }

    @Test
    @DisplayName("an enum reads case-insensitively, upper-cased in ROOT rather than the caller's locale")
    void coercesEnums() {
        assertThat(valueOf("high", Priority.class, RUSSIAN)).isEqualTo(Priority.HIGH);
        assertThat(valueOf("very low", Priority.class, RUSSIAN)).isEqualTo(Priority.VERY_LOW);
        assertThat(coerce(CellValue.ofText("urgent"), Priority.class, RUSSIAN).coercible()).isFalse();
    }

    @Test
    @DisplayName("a blank cell coerces to null for a reference type and is refused for a primitive")
    void handlesEmptyCells() {
        Coerced blank = coerce(CellValue.empty(), String.class, RUSSIAN);

        assertThat(blank.coercible())
                .as("a blank cell is a value the record may accept; Bean Validation decides whether it may."
                        + " An Optional could not express this, which is why Coerced exists")
                .isTrue();
        assertThat(blank.value()).isNull();
        assertThat(coerce(CellValue.empty(), int.class, RUSSIAN).coercible())
                .as("a primitive has no null, and importing 0 for a blank quantity is worse than refusing")
                .isFalse();
    }

    @Test
    @DisplayName("text that is not a number at all is refused")
    void refusesNonNumbers() {
        assertThat(coerce(CellValue.ofText("about three"), Integer.class, RUSSIAN).coercible()).isFalse();
    }

    private static Coerced coerce(CellValue cell, Class<?> target, CoercionContext context) {
        return CellCoercion.coerce(cell, target, context);
    }

    private static Object valueOf(String text, Class<?> target, CoercionContext context) {
        Coerced coerced = coerce(CellValue.ofText(text), target, context);
        assertThat(coerced.coercible())
                .as("'%s' was expected to coerce to %s", text, target.getSimpleName())
                .isTrue();
        return coerced.value();
    }

    /** A target enum for the enum coercion cases. */
    enum Priority {
        VERY_LOW, LOW, HIGH
    }
}
