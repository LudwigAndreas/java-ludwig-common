package ru.ludwigandreas.usersettings.api;

/**
 * A setting's value together with where it came from.
 *
 * <p>Returning the value alone was the obvious API and it is not sufficient, for two reasons that
 * show up immediately in practice. A settings screen has to render "inherited from your
 * organization" next to a field the user has not overridden, and cannot do that from the value. And
 * when an operator says "I set this at the tenant level and it has not taken effect", the only
 * useful answer is which layer actually supplied the value the user is seeing - without it, the
 * investigation is a series of guesses about precedence.
 *
 * @param value   the resolved value, which may be {@code null} when the definition's default is null
 * @param layer   which layer supplied it
 * @param scopeId the id within that layer - the subject, the role code, the tenant id
 * @param <T>     the setting's value type
 */
public record ResolvedValue<T>(T value, SettingLayer layer, String scopeId) {

    /** A resolved value always knows where it came from; the value itself may legitimately be null. */
    public ResolvedValue {
        if (layer == null) {
            throw new IllegalArgumentException("A resolved value needs a layer");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("A resolved value needs the id of the scope it came from");
        }
    }

    /** Nothing was stored anywhere; this is what the definition declares. */
    public boolean isDefault() {
        return layer == SettingLayer.DEFAULT;
    }

    /** The subject set this themselves, as opposed to inheriting it. */
    public boolean isOwnedBySubject() {
        return layer == SettingLayer.USER;
    }

    static <T> ResolvedValue<T> ofDefault(T value) {
        return new ResolvedValue<>(value, SettingLayer.DEFAULT, SettingScope.GLOBAL_ID);
    }
}
