package ru.ludwigandreas.usersettings.api;

/**
 * One change in a bulk update: a definition and the value it should take.
 *
 * <p>A {@code null} value means reset to whatever the layers below supply, which is the same thing
 * {@code SettingsWriter.reset} does for a single setting. Representing a reset as a null rather than
 * as a separate list keeps a bulk update a single ordered sequence - a settings form that clears one
 * field and sets another submits one request, and the two changes commit together or not at all.
 *
 * @param <T> the setting's value type
 */
public record SettingUpdate<T>(SettingDefinition<T> definition, T value) {

    /** The value may be null, meaning reset; the definition may not. */
    public SettingUpdate {
        if (definition == null) {
            throw new IllegalArgumentException("A setting update needs a definition");
        }
    }

    public static <T> SettingUpdate<T> of(SettingDefinition<T> definition, T value) {
        return new SettingUpdate<>(definition, value);
    }

    /** Clears the subject's own value, letting the layers below supply one again. */
    public static <T> SettingUpdate<T> reset(SettingDefinition<T> definition) {
        return new SettingUpdate<>(definition, null);
    }

    public boolean isReset() {
        return value == null;
    }
}
