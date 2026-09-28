package ru.ludwigandreas.usersettings.web.dto;

import java.util.Map;

/**
 * A bulk settings update: setting key to encoded value.
 *
 * <p>Values are text in the same encoding the read side returns, because a generic endpoint cannot
 * know the types. They are decoded through each setting's own converter, so a client that echoes
 * back what it was given always sends something valid.
 *
 * <p>A null value resets that setting. The whole map is applied in one transaction or not at all.
 */
public record UpdateSettingsRequest(Map<String, String> values) {

    public UpdateSettingsRequest {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
