package ru.ludwigandreas.usersettings.api;

import java.time.Instant;

/**
 * One stored value, as a {@code SettingValueSource} hands it back: still text, not yet converted,
 * and tagged with the scope it was found in.
 *
 * <p>Conversion happens after precedence has been applied, not before, so that a row whose encoding
 * no longer matches its definition costs nothing unless it was the row that would have won.
 *
 * @param scope     where this value was stored
 * @param key       the setting key
 * @param rawValue  the persisted text
 * @param typeId    the discriminator the text was written with; see {@code SettingValueTypes}
 * @param updatedAt when the value last changed, used to break ties and to order projected events
 */
public record ScopedValue(SettingScope scope, String key, String rawValue, String typeId, Instant updatedAt) {

    /** Rejects a value that names no scope or no setting, neither of which could be resolved. */
    public ScopedValue {
        if (scope == null) {
            throw new IllegalArgumentException("A scoped value needs a scope");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A scoped value needs a setting key");
        }
    }
}
