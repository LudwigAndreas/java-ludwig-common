package ru.ludwigandreas.usersettings.registry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.exception.SettingViolation;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.exception.UnknownSettingException;

/**
 * Every setting this service knows about, checked once at startup.
 *
 * <p>Four things are verified here, and each of them is a failure that would otherwise be discovered
 * by a user:
 *
 * <ul>
 *   <li><b>Duplicate keys.</b> Two sources declaring {@code user.timezone} differently means which
 *       declaration wins depends on bean ordering. Rejected with both declarations named.</li>
 *   <li><b>Unconvertible types.</b> A definition whose type nothing can encode would fail on the
 *       first write, to whoever happened to make it.</li>
 *   <li><b>Defaults that fail their own validation.</b> The most confusing failure of the set: every
 *       read succeeds, returning a value the module itself would refuse to store.</li>
 *   <li><b>Defaults that will not round-trip.</b> A default that encodes but decodes to something
 *       else means the value a user sees changes the first time anyone saves it.</li>
 * </ul>
 *
 * <p>The registry is immutable once built. Registering a setting at runtime is not supported and is
 * not an oversight - a definition is a compile-time artifact, and a mutable registry would make
 * "which settings exist" depend on what has run so far.
 */
@Slf4j
public class SettingDefinitionRegistry {

    private final Map<String, SettingDefinition<?>> byKey;
    private final Map<String, SettingValueConverter<?>> convertersByKey;

    /** Collects every contributed definition and runs the four startup checks described above. */
    public SettingDefinitionRegistry(List<SettingDefinitionSource> sources,
                                     SettingValueConverterRegistry converters) {
        Map<String, SettingDefinition<?>> collected = new LinkedHashMap<>();
        for (SettingDefinitionSource source : sources == null ? List.<SettingDefinitionSource>of() : sources) {
            for (SettingDefinition<?> definition : source.definitions()) {
                if (definition == null) {
                    throw new SettingConfigurationException(
                            "SettingDefinitionSource " + source.getClass().getName() + " contributed a null"
                                    + " definition");
                }
                SettingDefinition<?> existing = collected.putIfAbsent(definition.getKey(), definition);
                if (existing != null && existing != definition && !isSameDeclaration(existing, definition)) {
                    throw new SettingConfigurationException(
                            "Setting key " + definition.getKey() + " is declared twice with different"
                                    + " declarations: " + describe(existing) + " and " + describe(definition));
                }
            }
        }

        Map<String, SettingValueConverter<?>> resolved = new LinkedHashMap<>();
        for (SettingDefinition<?> definition : collected.values()) {
            resolved.put(definition.getKey(), check(definition, converters));
        }

        this.byKey = Map.copyOf(collected);
        this.convertersByKey = Map.copyOf(resolved);
        log.info("Registered {} setting definitions across {} categories",
                byKey.size(), byKey.values().stream().map(SettingDefinition::getCategory).distinct().count());
    }

    /** Every registered definition, in declaration order. */
    public Collection<SettingDefinition<?>> definitions() {
        return byKey.values();
    }

    public Optional<SettingDefinition<?>> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * The definition registered under this key.
     *
     * @throws UnknownSettingException when nothing registered that key - which is what a stored row
     *                                 for a setting this build no longer declares looks like
     */
    public SettingDefinition<?> require(String key) {
        SettingDefinition<?> definition = byKey.get(key);
        if (definition == null) {
            throw new UnknownSettingException(key);
        }
        return definition;
    }

    /**
     * Confirms that the definition a caller is holding is the one this service registered.
     *
     * <p>Checking the instance and not merely the key is what catches the case that would otherwise
     * be silent: a service holding a definition it declared but never contributed would read and
     * write against a key the registry knows under a <em>different</em> declaration, with a different
     * type or default, and get plausible-looking wrong answers.
     */
    public <T> SettingDefinition<T> require(SettingDefinition<T> definition) {
        SettingDefinition<?> registered = byKey.get(definition.getKey());
        if (registered == null) {
            throw new UnknownSettingException(definition.getKey());
        }
        if (registered != definition && !isSameDeclaration(registered, definition)) {
            throw new SettingConfigurationException(
                    "Setting " + definition.getKey() + " was looked up with " + describe(definition)
                            + " but is registered as " + describe(registered)
                            + "; the definition used at runtime must be the one that was registered");
        }
        return definition;
    }

    /** The converter resolved for this definition at startup. */
    @SuppressWarnings("unchecked")
    public <T> SettingValueConverter<T> converterFor(SettingDefinition<T> definition) {
        SettingValueConverter<?> converter = convertersByKey.get(definition.getKey());
        if (converter == null) {
            throw new UnknownSettingException(definition.getKey());
        }
        return (SettingValueConverter<T>) converter;
    }

    /** The converter resolved for a stored row's key, if this build still declares it. */
    public Optional<SettingValueConverter<?>> converterForKey(String key) {
        return Optional.ofNullable(convertersByKey.get(key));
    }

    private <T> SettingValueConverter<T> check(SettingDefinition<T> definition,
                                               SettingValueConverterRegistry converters) {
        SettingValueConverter<T> converter = converters.require(definition);

        Optional<SettingViolation> violation = definition.validate(definition.getDefaultValue());
        if (violation.isPresent()) {
            throw new SettingConfigurationException(
                    "The declared default for setting " + definition.getKey() + " fails its own validation ("
                            + violation.get().code() + "). A default that the module would refuse to store is"
                            + " served on every read until somebody sets a value.");
        }

        T defaultValue = definition.getDefaultValue();
        if (defaultValue != null) {
            roundTrip(definition, converter, defaultValue);
        }
        return converter;
    }

    private <T> void roundTrip(SettingDefinition<T> definition, SettingValueConverter<T> converter, T value) {
        T decoded;
        String encoded;
        try {
            encoded = converter.toStorage(value);
            decoded = converter.fromStorage(encoded);
        } catch (RuntimeException e) {
            throw new SettingConfigurationException(
                    "The declared default for setting " + definition.getKey() + " cannot be stored and read"
                            + " back with the " + converter.typeId() + " encoding", e);
        }
        if (!Objects.equals(value, decoded)) {
            throw new SettingConfigurationException(
                    "The declared default for setting " + definition.getKey() + " does not survive a round"
                            + " trip through the " + converter.typeId() + " encoding: it would change the"
                            + " first time anyone saved it");
        }
    }

    /**
     * Structural comparison, deliberately excluding the validator and the converter.
     *
     * <p>Those are functions, and two calls to the same factory produce two unequal instances - so
     * comparing them would report a conflict between a definition and an identical copy of itself,
     * which is exactly what a service that builds definitions from a factory (one opt-out per
     * notification category, say) would hit. What can be compared is what a caller can observe: the
     * type, the default, the category and the two flags.
     */
    private static boolean isSameDeclaration(SettingDefinition<?> left, SettingDefinition<?> right) {
        return left.getType().equals(right.getType())
                && Objects.equals(left.getDefaultValue(), right.getDefaultValue())
                && Objects.equals(left.getCategory(), right.getCategory())
                && left.isUserEditable() == right.isUserEditable()
                && left.isPii() == right.isPii()
                && left.isJsonEncoded() == right.isJsonEncoded();
    }

    /** Never includes the default value; see {@link SettingDefinition#toString()}. */
    private static String describe(SettingDefinition<?> definition) {
        return definition.getType().getSimpleName()
                + " in category '" + definition.getCategory() + "'"
                + (definition.isUserEditable() ? ", user-editable" : ", not user-editable")
                + (definition.isPii() ? ", PII" : "");
    }
}
