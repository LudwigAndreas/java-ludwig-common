package ru.ludwigandreas.usersettings.event;

/**
 * The event types owner mode publishes, and therefore the contract a projection consumes.
 *
 * <p>Constants because these strings are a wire contract: they appear in the outbox row, in the
 * Kafka record and in the projection's dispatch. Changing one is a breaking change to every
 * consuming service, which is much easier to notice when the value has a name than when it is a
 * literal in two files that have to agree.
 */
public final class SettingsEventTypes {

    /** A setting value was set, changed or removed at some scope. */
    public static final String USER_SETTING_CHANGED = "UserSettingChanged";

    /** A subject agreed to a specific version of a consent text. */
    public static final String CONSENT_GRANTED = "ConsentGranted";

    /** A subject withdrew a previous agreement. */
    public static final String CONSENT_REVOKED = "ConsentRevoked";

    /** Aggregate type for setting-value events. */
    public static final String SETTING_AGGREGATE = "UserSetting";

    /** Aggregate type for consent events. */
    public static final String CONSENT_AGGREGATE = "UserConsent";

    private SettingsEventTypes() {
    }
}
