package ru.ludwigandreas.usersettings.web.dto;

/**
 * One resolved setting, as the API renders it.
 *
 * <p>{@code layer} and {@code scopeId} are part of the response and not debugging extras. A settings
 * screen has to show "inherited from your organization" beside a field the subject has not
 * overridden, and it cannot derive that from the value - which is the same reason
 * {@code ResolvedValue} carries them.
 *
 * @param value        the value in its encoded form, which is also the form a write accepts, so a
 *                     client can round-trip what it was given without knowing the type
 * @param userEditable whether this subject may change it; a locked setting is shown, not hidden
 * @param pii          whether the value is personal data, so a client can decide not to log it
 */
public record SettingValueResponse(
        String key,
        String category,
        String value,
        String layer,
        String scopeId,
        boolean userEditable,
        boolean pii) {
}
