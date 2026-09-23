package ru.ludwigandreas.usersettings.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import ru.ludwigandreas.usersettings.api.FunctionalSettingValueConverter;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.api.SettingValueTypes;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;

/**
 * Finds the converter for a definition, and fails the context if there is not one.
 *
 * <p>Resolution is by <em>exact</em> type, never by assignability. Picking a converter for a
 * supertype would mean a definition declaring a subclass silently round-trips through the parent's
 * encoding and loses whatever the subclass added - a failure that shows up as data loss, long after
 * the deploy, and only for values that used the extra state.
 *
 * <p>Order of precedence, most explicit first:
 *
 * <ol>
 *   <li>a converter named on the definition itself</li>
 *   <li>the JSON encoder, when the definition opted into it</li>
 *   <li>a {@link SettingValueConverter} bean contributed by the application, which is how a service
 *       overrides a built-in for its own type</li>
 *   <li>a built-in ({@link BuiltInSettingValueConverters})</li>
 *   <li>the enum encoder, for any enum type</li>
 * </ol>
 *
 * <p>and nothing after that: a type reaching the end is a startup failure with the definition named.
 */
public class SettingValueConverterRegistry {

    private final Map<Class<?>, SettingValueConverter<?>> byType;
    private final Map<Class<?>, Class<?>> primitiveWrappers = BuiltInSettingValueConverters.primitiveWrappers();
    private final ObjectMapper objectMapper;

    /**
     * Enum and JSON converters are built on demand and kept, because building one per lookup would
     * allocate on the read path for no reason - they are stateless and interchangeable.
     */
    private final Map<Class<?>, SettingValueConverter<?>> derived = new ConcurrentHashMap<>();

    /**
     * Builds the lookup table of converters.
     *
     * @param contributed applied after the built-ins, so an application can replace one
     */
    public SettingValueConverterRegistry(List<SettingValueConverter<?>> contributed, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Map<Class<?>, SettingValueConverter<?>> resolved = new LinkedHashMap<>();
        for (SettingValueConverter<?> converter : BuiltInSettingValueConverters.all()) {
            resolved.put(converter.type(), converter);
        }
        // Contributed converters are applied last so that an application can replace a built-in - the
        // module's encoding of, say, Locale is a default, not a constraint on the platform.
        if (contributed != null) {
            for (SettingValueConverter<?> converter : contributed) {
                resolved.put(converter.type(), converter);
            }
        }
        this.byType = Map.copyOf(resolved);
    }

    /**
     * The converter for this definition.
     *
     * @throws SettingConfigurationException when nothing can encode the definition's type
     */
    public <T> SettingValueConverter<T> require(SettingDefinition<T> definition) {
        return find(definition).orElseThrow(() -> new SettingConfigurationException(
                "Setting " + definition.getKey() + " is declared as " + definition.getType().getName()
                        + ", which no converter handles. Register a SettingValueConverter bean for that"
                        + " type, name one on the definition, or mark the definition jsonEncoded(true)."));
    }

    /** The converter for this definition, if anything can encode its type. */
    @SuppressWarnings("unchecked")
    public <T> Optional<SettingValueConverter<T>> find(SettingDefinition<T> definition) {
        Optional<SettingValueConverter<T>> explicit = definition.explicitConverter();
        if (explicit.isPresent()) {
            return explicit;
        }
        Class<T> type = (Class<T>) primitiveWrappers.getOrDefault(definition.getType(), definition.getType());
        if (definition.isJsonEncoded()) {
            return Optional.of((SettingValueConverter<T>) derived.computeIfAbsent(type, this::jsonConverter));
        }
        SettingValueConverter<?> registered = byType.get(type);
        if (registered != null) {
            return Optional.of((SettingValueConverter<T>) registered);
        }
        if (type.isEnum()) {
            return Optional.of((SettingValueConverter<T>)
                    derived.computeIfAbsent(type, SettingValueConverterRegistry::enumConverter));
        }
        return Optional.empty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SettingValueConverter<?> enumConverter(Class<?> type) {
        Class rawType = type;
        return new FunctionalSettingValueConverter<>(rawType, SettingValueTypes.ENUM,
                value -> ((Enum<?>) value).name(),
                raw -> Enum.valueOf(rawType, raw));
    }

    private <T> SettingValueConverter<T> jsonConverter(Class<T> type) {
        return new FunctionalSettingValueConverter<>(type, SettingValueTypes.JSON,
                value -> {
                    try {
                        return objectMapper.writeValueAsString(value);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Cannot serialize a " + type.getSimpleName(), e);
                    }
                },
                raw -> {
                    try {
                        return objectMapper.readValue(raw, type);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Cannot deserialize a " + type.getSimpleName(), e);
                    }
                });
    }
}
