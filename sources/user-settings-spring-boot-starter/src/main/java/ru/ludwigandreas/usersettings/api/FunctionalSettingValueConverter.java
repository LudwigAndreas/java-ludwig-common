package ru.ludwigandreas.usersettings.api;

import java.util.function.Function;

/**
 * A converter assembled from two functions, which is what every built-in one is.
 *
 * <p>A record rather than a lambda per direction because a converter has to answer three questions -
 * its type, its discriminator, and how to encode - and only one of those is a function.
 *
 * @param <T> the setting's value type
 */
public record FunctionalSettingValueConverter<T>(
        Class<T> type,
        String typeId,
        Function<T, String> encoder,
        Function<String, T> decoder) implements SettingValueConverter<T> {

    /** All four parts are required: a converter that cannot name its type cannot be looked up. */
    public FunctionalSettingValueConverter {
        if (type == null || typeId == null || encoder == null || decoder == null) {
            throw new IllegalArgumentException("A converter needs a type, a discriminator and both directions");
        }
    }

    @Override
    public String toStorage(T value) {
        return encoder.apply(value);
    }

    @Override
    public T fromStorage(String raw) {
        return decoder.apply(raw);
    }
}
