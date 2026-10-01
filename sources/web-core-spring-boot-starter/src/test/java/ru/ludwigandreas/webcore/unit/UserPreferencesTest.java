package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.FormatStyle;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext;
import ru.ludwigandreas.webcore.preference.UserPreferenceDefaults;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The value type: its invariants, the ambient read, the binding scope and the derived formatters.
 *
 * <p>Every test here resets both the thread's context and the process-wide defaults afterwards. Both
 * are static state, and a test that leaks one makes the suite pass in one order and fail in another -
 * which is the failure mode that takes longest to diagnose and is cheapest to prevent.
 */
class UserPreferencesTest {

    private static final ZoneId YEKATERINBURG = ZoneId.of("Asia/Yekaterinburg");

    @AfterEach
    void resetStaticState() {
        LocaleContextHolder.resetLocaleContext();
        UserPreferenceDefaults.reset();
    }

    @Test
    @DisplayName("neither dimension may be null, because every caller of current() would then have to check")
    void bothDimensionsAreRequired() {
        assertThatThrownBy(() -> new UserPreferences(null, ZoneOffset.UTC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locale");
        assertThatThrownBy(() -> new UserPreferences(Locale.ENGLISH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zone");
    }

    @Test
    @DisplayName("off a request thread, current() answers the configured defaults and never throws")
    void currentFallsBackToConfiguredDefaults() {
        UserPreferenceDefaults.install(new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG));

        assertThat(UserPreferences.current())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG));
    }

    @Test
    @DisplayName("with nothing installed at all, current() is UTC and English - not the container's zone")
    void currentFallsBackToTheBuiltInFallback() {
        assertThat(UserPreferences.current()).isEqualTo(UserPreferences.FALLBACK);
        assertThat(UserPreferences.FALLBACK.zone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("currentIfResolved() is empty unless preferences were actually bound")
    void currentIfResolvedDistinguishesADefaultFromAChoice() {
        UserPreferenceDefaults.install(new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG));

        assertThat(UserPreferences.currentIfResolved()).isEmpty();

        UserPreferences bound = new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG);
        try (UserPreferences.Scope scope = bound.bind()) {
            assertThat(UserPreferences.currentIfResolved()).contains(bound);
        }
    }

    @Test
    @DisplayName("a bound scope restores the previous context on close, including when there was none")
    void bindRestoresThePreviousContext() {
        UserPreferences first = new UserPreferences(Locale.ENGLISH, ZoneOffset.UTC);
        UserPreferences second = new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG);

        try (UserPreferences.Scope outer = first.bind()) {
            assertThat(UserPreferences.current()).isEqualTo(first);
            try (UserPreferences.Scope inner = second.bind()) {
                assertThat(UserPreferences.current()).isEqualTo(second);
            }
            assertThat(UserPreferences.current()).isEqualTo(first);
        }

        // The point of the assertion: a pooled thread must not carry one caller's preferences into
        // the next caller's task, so closing the outermost scope has to clear the context rather
        // than leave the last value in place.
        assertThat(UserPreferences.currentIfResolved()).isEmpty();
    }

    @Test
    @DisplayName("a generic timezone-aware context is read, not ignored")
    void aForeignTimeZoneAwareContextIsHonoured() {
        LocaleContextHolder.setLocaleContext(new SimpleTimeZoneAwareLocaleContext(
                Locale.forLanguageTag("ru"), java.util.TimeZone.getTimeZone(YEKATERINBURG)));

        UserPreferences current = UserPreferences.current();

        assertThat(current.locale()).isEqualTo(Locale.forLanguageTag("ru"));
        assertThat(current.zone().getRules()).isEqualTo(YEKATERINBURG.getRules());
    }

    @Test
    @DisplayName("a locale-only context contributes its locale and leaves the zone to the defaults")
    void aLocaleOnlyContextContributesOnlyALocale() {
        UserPreferenceDefaults.install(new UserPreferences(Locale.ENGLISH, YEKATERINBURG));
        LocaleContextHolder.setLocaleContext(new SimpleLocaleContext(Locale.forLanguageTag("ru")));

        assertThat(UserPreferences.current())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG));
    }

    @Test
    @DisplayName("an instant becomes the caller's local date, which is the whole reason the zone matters")
    void theLocalDateDependsOnTheZone() {
        Instant newYearInUtc = Instant.parse("2024-01-01T02:00:00Z");

        assertThat(new UserPreferences(Locale.ENGLISH, ZoneOffset.UTC).dateAt(newYearInUtc))
                .isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(new UserPreferences(Locale.ENGLISH, ZoneId.of("America/New_York")).dateAt(newYearInUtc))
                .isEqualTo(LocalDate.of(2023, 12, 31));
    }

    @Test
    @DisplayName("null in, null out, because a mapper maps absent fields too")
    void conversionsPassNullThrough() {
        UserPreferences preferences = new UserPreferences(Locale.ENGLISH, ZoneOffset.UTC);

        assertThat(preferences.at(null)).isNull();
        assertThat(preferences.dateAt(null)).isNull();
    }

    @Test
    @DisplayName("the first day of the week is derived from the locale, and the REGION is what carries it")
    void theFirstDayOfWeekIsDerived() {
        assertThat(new UserPreferences(Locale.forLanguageTag("ru-RU"), ZoneOffset.UTC).firstDayOfWeek())
                .isEqualTo(DayOfWeek.MONDAY);
        assertThat(new UserPreferences(Locale.US, ZoneOffset.UTC).firstDayOfWeek())
                .isEqualTo(DayOfWeek.SUNDAY);

        // Asserted rather than merely documented, because it is counter-intuitive and because it is
        // the reason UserPreferenceResolver answers the caller's own tag instead of narrowing it to
        // the supported language: the JDK holds first-day-of-week as region data, so a language-only
        // Russian locale gets the CLDR root's Sunday. A resolver that narrowed ru-RU to ru would hand
        // every Russian user an American calendar, and nothing would have failed.
        assertThat(new UserPreferences(Locale.forLanguageTag("ru"), ZoneOffset.UTC).firstDayOfWeek())
                .isEqualTo(DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("a number is formatted with the locale's own separators, which swap roles between the two")
    void numbersUseTheLocaleSeparator() {
        String english = new UserPreferences(Locale.US, ZoneOffset.UTC).numberFormat().format(1234.56);
        String russian = new UserPreferences(Locale.forLanguageTag("ru-RU"), ZoneOffset.UTC)
                .numberFormat().format(1234.56);

        assertThat(english).isEqualTo("1,234.56");
        // The comma is the DECIMAL separator in Russian and the GROUPING separator in English, which
        // is why rendering one to a reader of the other is read as a different quantity rather than
        // as a formatting quirk. The grouping separator itself is asserted only as "not a comma": it
        // is a space whose exact codepoint has moved between CLDR revisions, and pinning it would
        // make this fail on a JDK upgrade over something it is not testing.
        assertThat(russian).endsWith(",56");
        assertThat(russian.charAt(1)).isNotEqualTo(',');
    }

    @Test
    @DisplayName("a formatter carries both the locale and the zone, so it can format a bare Instant")
    void formattersCarryBothDimensions() {
        UserPreferences preferences = new UserPreferences(Locale.US, ZoneId.of("America/New_York"));

        String formatted = preferences.dateTimeFormatter(FormatStyle.SHORT)
                .format(Instant.parse("2024-01-01T02:00:00Z"));

        assertThat(formatted).contains("12/31/23");
    }
}
