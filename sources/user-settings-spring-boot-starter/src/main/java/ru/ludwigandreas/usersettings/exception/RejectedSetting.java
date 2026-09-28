package ru.ludwigandreas.usersettings.exception;

/**
 * One setting a write was refused for, and why.
 *
 * @param settingKey which setting was rejected
 * @param violation  the reason, as a message code and its arguments
 */
public record RejectedSetting(String settingKey, SettingViolation violation) {

    /** Rejects an incomplete rejection: both the setting and the reason are needed to report it. */
    public RejectedSetting {
        if (settingKey == null || settingKey.isBlank()) {
            throw new IllegalArgumentException("A rejected setting needs a key");
        }
        if (violation == null) {
            throw new IllegalArgumentException("A rejected setting needs a violation");
        }
    }
}
