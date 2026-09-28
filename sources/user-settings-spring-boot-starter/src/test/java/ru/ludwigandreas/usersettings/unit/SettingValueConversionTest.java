package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.api.SettingValueTypes;
import ru.ludwigandreas.usersettings.wellknown.DigestPreference;
import ru.ludwigandreas.usersettings.wellknown.QuietHours;

/**
 * Storage encodings. Every one of these has to round-trip exactly, because the value a user sees
 * after saving must be the value they chose.
 */
class SettingValueConversionTest {

    private final SettingValueConverterRegistry registry = new SettingValueConverterRegistry(
            List.of(), new ObjectMapper().registerModule(new JavaTimeModule()));

    private <T> T roundTrip(Class<T> type, T value) {
        SettingDefinition<T> definition = SettingDefinition.of("test.value", type).build();
        SettingValueConverter<T> converter = registry.require(definition);
        return converter.fromStorage(converter.toStorage(value));
    }

    @Test
    @DisplayName("every built-in type survives a round trip")
    void built_in_types_round_trip() {
        assertThat(roundTrip(String.class, "hello")).isEqualTo("hello");
        assertThat(roundTrip(Boolean.class, Boolean.TRUE)).isTrue();
        assertThat(roundTrip(Integer.class, 42)).isEqualTo(42);
        assertThat(roundTrip(Long.class, 42L)).isEqualTo(42L);
        assertThat(roundTrip(Double.class, 1.5)).isEqualTo(1.5);
        assertThat(roundTrip(Instant.class, Instant.parse("2026-01-02T03:04:05Z")))
                .isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(roundTrip(Duration.class, Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(30));
        assertThat(roundTrip(LocalTime.class, LocalTime.of(22, 30))).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    @DisplayName("a zone is stored by its own id, not as an offset")
    void zone_is_stored_as_a_region_id() {
        // An offset does not survive a daylight-saving change, so "Europe/Moscow" is the value and
        // "+03:00" would be a bug that only shows up twice a year.
        SettingDefinition<ZoneId> definition = SettingDefinition.of("test.zone", ZoneId.class).build();
        SettingValueConverter<ZoneId> converter = registry.require(definition);

        assertThat(converter.toStorage(ZoneId.of("Europe/Moscow"))).isEqualTo("Europe/Moscow");
        assertThat(converter.typeId()).isEqualTo(SettingValueTypes.ZONE_ID);
    }

    @Test
    @DisplayName("a locale is stored as a BCP 47 tag")
    void locale_is_stored_as_a_language_tag() {
        SettingDefinition<Locale> definition = SettingDefinition.of("test.locale", Locale.class).build();
        SettingValueConverter<Locale> converter = registry.require(definition);

        assertThat(converter.toStorage(Locale.forLanguageTag("ru-RU"))).isEqualTo("ru-RU");
        assertThat(roundTrip(Locale.class, Locale.forLanguageTag("ru-RU")))
                .isEqualTo(Locale.forLanguageTag("ru-RU"));
    }

    @Test
    @DisplayName("boolean parsing is strict")
    void boolean_parsing_rejects_anything_else() {
        // Boolean.parseBoolean maps every unrecognized string to false, which would silently turn a
        // stored "yes" into "opted out" - exactly the class of bug this module must not have.
        SettingDefinition<Boolean> definition = SettingDefinition.of("test.flag", Boolean.class).build();
        SettingValueConverter<Boolean> converter = registry.require(definition);

        assertThat(converter.fromStorage("TRUE")).isTrue();
        assertThatThrownBy(() -> converter.fromStorage("yes")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an enum is stored by constant name")
    void enum_is_stored_by_name() {
        assertThat(roundTrip(DigestPreference.class, DigestPreference.WEEKLY))
                .isEqualTo(DigestPreference.WEEKLY);
    }

    @Test
    @DisplayName("a JSON-encoded setting round-trips its whole structure")
    void json_encoded_value_round_trips() {
        SettingDefinition<QuietHours> definition = SettingDefinition
                .of("test.quiet-hours", QuietHours.class)
                .jsonEncoded(true)
                .build();
        SettingValueConverter<QuietHours> converter = registry.require(definition);
        QuietHours window = new QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(converter.typeId()).isEqualTo(SettingValueTypes.JSON);
        assertThat(converter.fromStorage(converter.toStorage(window))).isEqualTo(window);
    }

    @Test
    @DisplayName("JSON is not applied automatically, so an unconvertible type stays unconvertible")
    void json_is_opt_in_only() {
        // If JSON were a fallback, no type would ever be unconvertible and "a definition whose type
        // has no converter" would stop being a startup failure.
        SettingDefinition<QuietHours> withoutOptIn =
                SettingDefinition.of("test.quiet-hours", QuietHours.class).build();

        assertThat(registry.find(withoutOptIn)).isEmpty();
    }

    @Test
    @DisplayName("a contributed converter replaces a built-in for the same type")
    void contributed_converter_overrides_a_built_in() {
        SettingValueConverterRegistry overridden = new SettingValueConverterRegistry(
                List.of(new ru.ludwigandreas.usersettings.api.FunctionalSettingValueConverter<>(
                        String.class, SettingValueTypes.STRING,
                        value -> value.toUpperCase(Locale.ROOT), raw -> raw)),
                new ObjectMapper());
        SettingDefinition<String> definition = SettingDefinition.of("test.text", String.class).build();

        assertThat(overridden.require(definition).toStorage("shout")).isEqualTo("SHOUT");
    }

    @Test
    @DisplayName("a primitive type is accepted and handled by its wrapper's converter")
    void primitive_types_are_accepted() {
        SettingDefinition<Boolean> primitive = SettingDefinition
                .of("test.primitive", boolean.class)
                .build();

        assertThat(registry.require(primitive).typeId()).isEqualTo(SettingValueTypes.BOOLEAN);
    }
}
