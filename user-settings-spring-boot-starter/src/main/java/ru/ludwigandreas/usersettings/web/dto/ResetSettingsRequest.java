package ru.ludwigandreas.usersettings.web.dto;

import java.util.List;

/**
 * Settings to clear, so the layers below supply a value again.
 *
 * <p>A body rather than a path variable per key, for two reasons: setting keys contain dots, which
 * are awkward in a path and historically ambiguous with content-type suffixes, and clearing several
 * settings at once is a single thing a user asked for and should be a single transaction.
 */
public record ResetSettingsRequest(List<String> keys) {

    public ResetSettingsRequest {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
