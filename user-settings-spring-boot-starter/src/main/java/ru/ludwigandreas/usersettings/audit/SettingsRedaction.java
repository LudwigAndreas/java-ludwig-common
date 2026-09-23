package ru.ludwigandreas.usersettings.audit;

import ru.ludwigandreas.usersettings.api.SettingDefinition;

/**
 * What a PII-flagged value looks like everywhere outside the settings table itself.
 *
 * <p>A fixed marker rather than a hash or a truncation. A hash is reversible for any value drawn
 * from a small set - which most settings are, and a phone number certainly is - and a truncation
 * leaks exactly the part of an identifier that identifies. Neither is worth the debuggability they
 * buy, because the audit row already records which setting changed, when, and by whom, and that is
 * what the trail is read for.
 *
 * <p>The marker is deliberately not configurable. A deployment that could change it could set it to
 * the empty string, at which point a redacted value and a value that was never set become the same
 * row.
 */
public final class SettingsRedaction {

    /** Stored in place of a PII-flagged value, and published in place of one. */
    public static final String REDACTED = "[redacted]";

    private SettingsRedaction() {
    }

    /** The value as the audit trail should hold it. */
    public static String forAudit(SettingDefinition<?> definition, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        return definition.isPii() ? REDACTED : rawValue;
    }
}
