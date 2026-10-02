package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.webcore.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferenceDefaults;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The per-dimension fold over the ordered sources.
 *
 * <p>The behaviour being pinned is the one that a whole-value SPI could not express: a source that
 * answers one dimension and abstains on the other has to lose only the dimension it abstained on.
 */
class UserPreferenceResolutionTest {

    private static final ZoneId YEKATERINBURG = ZoneId.of("Asia/Yekaterinburg");

    private static final UserPreferences CONFIGURED =
            new UserPreferences(Locale.ENGLISH, ZoneOffset.UTC);

    @AfterEach
    void resetStaticState() {
        UserPreferenceDefaults.reset();
    }

    @Test
    @DisplayName("a stored zone with no stored locale still lets the header decide the locale")
    void dimensionsResolveIndependently() {
        UserPreferenceResolver resolver = resolver(
                source(UserPreferenceSource.STORED_ORDER, null, YEKATERINBURG),
                source(UserPreferenceSource.REQUEST_ORDER, Locale.forLanguageTag("ru"), null),
                new ConfiguredPreferenceSource(CONFIGURED));

        assertThat(resolver.resolve())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG));
    }

    @Test
    @DisplayName("a stored choice outranks a request header, because the header is the browser's opinion")
    void storedBeatsHeader() {
        UserPreferenceResolver resolver = resolver(
                source(UserPreferenceSource.REQUEST_ORDER, Locale.ENGLISH, ZoneOffset.UTC),
                source(UserPreferenceSource.STORED_ORDER, Locale.forLanguageTag("ru"), YEKATERINBURG),
                new ConfiguredPreferenceSource(CONFIGURED));

        assertThat(resolver.resolve())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG));
    }

    @Test
    @DisplayName("the sources are sorted here, so a caller does not have to have sorted them")
    void orderIsAppliedRatherThanAssumed() {
        UserPreferenceResolver resolver = resolver(
                new ConfiguredPreferenceSource(CONFIGURED),
                source(UserPreferenceSource.STORED_ORDER, Locale.forLanguageTag("ru"), YEKATERINBURG));

        assertThat(resolver.resolve().zone()).isEqualTo(YEKATERINBURG);
    }

    @Test
    @DisplayName("with nothing but the configured source, resolution is still total")
    void configuredSourceAlwaysAnswers() {
        assertThat(resolver(new ConfiguredPreferenceSource(CONFIGURED)).resolve()).isEqualTo(CONFIGURED);
    }

    @Test
    @DisplayName("an unsupported locale yields the default in full, whichever source supplied it")
    void anUnsupportedStoredLocaleIsNotHalfHonoured() {
        UserPreferenceDefaults.install(CONFIGURED);
        UserPreferenceResolver resolver = new UserPreferenceResolver(
                List.of(source(UserPreferenceSource.STORED_ORDER, Locale.GERMAN, null),
                        new ConfiguredPreferenceSource(CONFIGURED)),
                List.of(Locale.ENGLISH, Locale.forLanguageTag("ru")));

        // Not German, and deliberately not "German with English fallback for the missing keys": the
        // i18n-bundles capability requires the whole response to be in one language, and a stored
        // locale is written by a platform-wide settings screen that does not know which bundles this
        // service ships.
        assertThat(resolver.resolve().locale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    @DisplayName("a supported language keeps the caller's region, which several things derive from")
    void aLanguageMatchKeepsTheRegion() {
        UserPreferenceResolver resolver = new UserPreferenceResolver(
                List.of(source(UserPreferenceSource.STORED_ORDER, Locale.forLanguageTag("ru-RU"), null),
                        new ConfiguredPreferenceSource(CONFIGURED)),
                List.of(Locale.ENGLISH, Locale.forLanguageTag("ru")));

        assertThat(resolver.resolve().locale()).isEqualTo(Locale.forLanguageTag("ru-RU"));
    }

    @Test
    @DisplayName("an empty supported-locale list is no restriction at all")
    void noSupportedLocalesMeansNoRestriction() {
        assertThat(resolver(source(UserPreferenceSource.STORED_ORDER, Locale.GERMAN, null),
                new ConfiguredPreferenceSource(CONFIGURED)).resolve().locale())
                .isEqualTo(Locale.GERMAN);
    }

    @Test
    @DisplayName("the active sources are reportable, which is the only signal for a forgotten declaration")
    void sourceNamesAreReported() {
        assertThat(resolver(new ConfiguredPreferenceSource(CONFIGURED)).sourceNames())
                .containsExactly("ConfiguredPreferenceSource");
    }

    private static UserPreferenceResolver resolver(UserPreferenceSource... sources) {
        return new UserPreferenceResolver(List.of(sources), List.of());
    }

    private static UserPreferenceSource source(int order, Locale locale, ZoneId zone) {
        return new UserPreferenceSource() {
            @Override
            public Optional<Locale> locale() {
                return Optional.ofNullable(locale);
            }

            @Override
            public Optional<ZoneId> zone() {
                return Optional.ofNullable(zone);
            }

            @Override
            public int getOrder() {
                return order;
            }
        };
    }
}
