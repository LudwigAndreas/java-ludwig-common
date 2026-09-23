package ru.ludwigandreas.usersettings.api;

import ru.ludwigandreas.usersettings.exception.SettingViolation;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * One setting, as the consuming service declares it: typed, named once, and checked at startup.
 *
 * <pre>{@code
 * public static final SettingDefinition<ZoneId> TIMEZONE = SettingDefinition
 *         .of("user.timezone", ZoneId.class)
 *         .defaultValue(ZoneId.of("UTC"))
 *         .category("locale")
 *         .userEditable(true)
 *         .build();
 * }</pre>
 *
 * <h2>Why definitions are code and not rows</h2>
 *
 * <p>This is the design decision the whole module is built around. Settings schemas differ per
 * platform; the engine does not. The obvious way to make an engine reusable across platforms is to
 * let each one declare its settings as data - a table of keys, types and defaults - and that is
 * precisely how a settings service becomes an entity-attribute-value store that nobody can refactor:
 * every key is a string, every value is untyped at the call site, renaming one is a search through
 * string literals, and deleting one is unknowable.
 *
 * <p>Declaring them as constants instead moves all of that to the compiler. A definition is
 * referenced, not spelled; its type is the type callers get back; an unused one shows up as an
 * unused field; and a rename is a rename. The storage is still opaque - a value is text plus a
 * discriminator, converted at the boundary - but nothing joins or reports on it, which is the only
 * condition under which that trade is acceptable.
 *
 * <p>The cost is honest and worth stating: adding a setting requires a deploy of the service that
 * declares it. In exchange, no service can be handed a setting it does not understand.
 *
 * @param <T> the setting's value type
 */
@Getter
@EqualsAndHashCode(of = "key")
public final class SettingDefinition<T> {

    /**
     * Keys are lowercase, dot-separated segments. Enforced rather than merely recommended because a
     * key is a wire identifier: it appears in change events, in audit rows and in a projection's
     * tables, and a deployment that allowed {@code User.TimeZone} alongside {@code user.timezone}
     * would have two settings that every human reader would call one.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9]*([.\\-][a-z0-9]+)*$");

    private static final int MAX_KEY_LENGTH = 128;

    /** Stable identifier, unique across the whole registry. */
    private final String key;

    /** The type callers get back, and the type a converter has to be resolvable for. */
    private final Class<T> type;

    /**
     * What {@link SettingLayer#DEFAULT} supplies when nothing is set anywhere. May be {@code null},
     * which means "no value" rather than "empty" - a caller then gets {@code null} and decides.
     */
    private final T defaultValue;

    /** Grouping label for UIs and for the per-category write metric. Never used in resolution. */
    private final String category;

    /**
     * Whether the owning user may change this themselves. {@code false} by default: a setting that
     * is writable by its subject is a decision, and defaulting the other way would make every new
     * definition silently user-writable until someone noticed.
     */
    private final boolean userEditable;

    /**
     * Whether this setting's value is personal data. A flagged value is redacted in the audit trail,
     * in logs and in any problem document, and never appears in a metric tag.
     */
    private final boolean pii;

    /** Optional constraint, applied to the declared default at startup and to every write. */
    private final SettingValidator<T> validator;

    /** Optional explicit converter, overriding whatever the registry would resolve for {@link #type}. */
    private final SettingValueConverter<T> converter;

    /**
     * Store this setting as JSON rather than requiring a converter for its type.
     *
     * <p>Opt-in, and deliberately not a fallback the registry applies on its own: if any type could
     * be JSON-encoded automatically, then no type would ever be unconvertible, and "a definition
     * whose type has no converter" would stop being a startup failure. Marking it here keeps the
     * blob a visible choice in the definition rather than an accident of what happened to compile.
     */
    private final boolean jsonEncoded;

    /** Human-readable purpose, for generated documentation and admin screens. */
    private final String description;

    // SUPPRESS CHECKSTYLE ParameterNumber - the canonical constructor behind @Builder. The rule
    // exists to catch call sites nobody can read; nothing calls this positionally, because the
    // builder is the only way in.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Builder
    private SettingDefinition(String key, Class<T> type, T defaultValue, String category,
                              boolean userEditable, boolean pii, SettingValidator<T> validator,
                              SettingValueConverter<T> converter, boolean jsonEncoded, String description) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A setting definition needs a key");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Setting key is longer than " + MAX_KEY_LENGTH + " characters: " + key);
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Setting key must be lowercase dot-separated segments (e.g. user.timezone), was: " + key);
        }
        if (type == null) {
            throw new IllegalArgumentException("Setting definition " + key + " needs a type");
        }
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.category = category == null || category.isBlank() ? "general" : category;
        this.userEditable = userEditable;
        this.pii = pii;
        this.validator = validator;
        this.converter = converter;
        this.jsonEncoded = jsonEncoded;
        this.description = description;
    }

    /** Entry point for the builder, so the two things a definition cannot omit are named up front. */
    public static <T> SettingDefinitionBuilder<T> of(String key, Class<T> type) {
        return SettingDefinition.<T>builder().key(key).type(type);
    }

    /** Applies this definition's own constraint, if it declared one. */
    public Optional<SettingViolation> validate(T value) {
        return validator == null ? Optional.empty() : validator.validate(value);
    }

    public Optional<SettingValueConverter<T>> explicitConverter() {
        return Optional.ofNullable(converter);
    }

    /**
     * Never includes the default value. A default is not personal data, but this string ends up in
     * startup logs and exception messages, and a definition carrying a sample of what the value
     * looks like is the first step towards one carrying an actual value.
     */
    @Override
    public String toString() {
        return "SettingDefinition(" + key + ": " + type.getSimpleName() + ")";
    }
}
