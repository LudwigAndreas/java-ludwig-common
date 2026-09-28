package ru.ludwigandreas.usersettings.convert;

import ru.ludwigandreas.usersettings.api.FunctionalSettingValueConverter;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.api.SettingValueTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The converters every deployment gets without declaring anything.
 *
 * <p>The list is short on purpose. These are the types a setting is actually expressed in - a flag,
 * a count, a locale, a zone, a time of day, a window - and each one has a single unambiguous textual
 * form that survives a round trip through a {@code TEXT} column and through a Kafka event. Types with
 * no such canonical form are not here: a definition needing one either contributes a
 * {@link SettingValueConverter} bean or marks itself JSON-encoded, both of which are visible choices
 * rather than a silent {@code toString()}.
 *
 * <p>{@link ZoneId} and {@link Locale} in particular are stored in their own canonical text form
 * ({@code Europe/Moscow}, {@code ru-RU}) rather than as an offset or a three-letter code, because an
 * offset does not survive a daylight-saving change and a legacy locale code does not round-trip.
 */
public final class BuiltInSettingValueConverters {

    private BuiltInSettingValueConverters() {
    }

    /**
     * Every built-in converter, in no particular order - they are looked up by exact type, never
     * scanned.
     */
    public static List<SettingValueConverter<?>> all() {
        return List.of(
                new FunctionalSettingValueConverter<>(
                        String.class, SettingValueTypes.STRING, value -> value, raw -> raw),
                new FunctionalSettingValueConverter<>(
                        Boolean.class, SettingValueTypes.BOOLEAN, String::valueOf,
                        BuiltInSettingValueConverters::parseBoolean),
                new FunctionalSettingValueConverter<>(
                        Integer.class, SettingValueTypes.INTEGER, String::valueOf, Integer::valueOf),
                new FunctionalSettingValueConverter<>(
                        Long.class, SettingValueTypes.LONG, String::valueOf, Long::valueOf),
                new FunctionalSettingValueConverter<>(
                        Double.class, SettingValueTypes.DOUBLE, String::valueOf, Double::valueOf),
                new FunctionalSettingValueConverter<>(
                        BigDecimal.class, SettingValueTypes.DECIMAL, BigDecimal::toPlainString, BigDecimal::new),
                new FunctionalSettingValueConverter<>(
                        Instant.class, SettingValueTypes.INSTANT, Instant::toString, Instant::parse),
                new FunctionalSettingValueConverter<>(
                        Duration.class, SettingValueTypes.DURATION, Duration::toString, Duration::parse),
                new FunctionalSettingValueConverter<>(
                        LocalTime.class, SettingValueTypes.LOCAL_TIME, LocalTime::toString, LocalTime::parse),
                new FunctionalSettingValueConverter<>(
                        ZoneId.class, SettingValueTypes.ZONE_ID, ZoneId::getId, ZoneId::of),
                new FunctionalSettingValueConverter<>(
                        Locale.class, SettingValueTypes.LOCALE, Locale::toLanguageTag,
                        Locale::forLanguageTag),
                new FunctionalSettingValueConverter<>(
                        UUID.class, SettingValueTypes.UUID, UUID::toString, UUID::fromString));
    }

    /**
     * Primitive classes mapped to the wrapper converter that handles them.
     *
     * <p>{@code SettingDefinition.of("x", boolean.class)} is a mistake that compiles, and rejecting it
     * at startup with "no converter for boolean" would be a confusing way to say "write Boolean".
     * Accepting both spellings is friendlier and cannot be ambiguous.
     */
    public static Map<Class<?>, Class<?>> primitiveWrappers() {
        Map<Class<?>, Class<?>> wrappers = new LinkedHashMap<>();
        wrappers.put(boolean.class, Boolean.class);
        wrappers.put(int.class, Integer.class);
        wrappers.put(long.class, Long.class);
        wrappers.put(double.class, Double.class);
        return Map.copyOf(wrappers);
    }

    /**
     * Strict, unlike {@link Boolean#parseBoolean(String)}, which maps every unrecognized string to
     * {@code false}. A stored value of {@code "yes"} silently becoming "opted out" is exactly the
     * class of bug this module must not have.
     */
    private static Boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Not a boolean: " + raw);
    }
}
