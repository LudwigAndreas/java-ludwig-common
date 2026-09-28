package ru.ludwigandreas.usersettings.wellknown;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;

/**
 * The settings the platform shares, for services that want them.
 *
 * <h2>Opt-in, and why that matters</h2>
 *
 * <p>Nothing here is registered automatically. A service that wants these declares a
 * {@link SettingDefinitionSource} bean naming them - {@link #source()} is the shortcut - and a
 * service that does not simply never sees them.
 *
 * <p>Auto-registering them would have been one line of autoconfiguration and would have broken the
 * module's central promise: that {@code getAll} returns exactly the settings this service declares.
 * A service with four settings of its own would suddenly resolve nine, four of which it has no
 * screen for, no validation opinion about and no intention of honouring - and a user editing them
 * would watch their preference be ignored.
 *
 * <h2>The dependency direction</h2>
 *
 * <p>Several of these describe notification behaviour, and this module deliberately does not depend
 * on {@code notification-service} - the dependency runs the other way. The <em>preference</em>
 * belongs with the user's other settings; what a service does with it is that service's business.
 * A settings module that imported a notification service's types would be unusable by anyone not
 * running that service, which is the opposite of what a platform module is for.
 */
public final class WellKnownSettings {

    /** Category every definition here is filed under, so a UI can group them without a lookup table. */
    public static final String LOCALE_CATEGORY = "locale";

    /** Category for the notification-related preferences. */
    public static final String NOTIFICATIONS_CATEGORY = "notifications";

    /**
     * The language a subject wants to be addressed in.
     *
     * <p>Not PII. A locale is shared by millions of people and identifies nobody, and flagging it
     * would redact it from the audit trail for no benefit - at which point "why is this user
     * suddenly getting English" becomes unanswerable.
     */
    public static final SettingDefinition<Locale> LOCALE = SettingDefinition
            .of("user.locale", Locale.class)
            .defaultValue(Locale.ENGLISH)
            .category(LOCALE_CATEGORY)
            .userEditable(true)
            .description("Language the subject is addressed in, as a BCP 47 tag")
            .build();

    /**
     * The timezone a subject's times are rendered and scheduled in.
     *
     * <p>Defaults to UTC rather than to the server's zone. A server-zone default is invisible in
     * development, where they coincide, and wrong in production for every subject who is not in the
     * datacentre's timezone - and it changes meaning when the service is deployed elsewhere.
     */
    public static final SettingDefinition<ZoneId> TIMEZONE = SettingDefinition
            .of("user.timezone", ZoneId.class)
            .defaultValue(ZoneId.of("UTC"))
            .category(LOCALE_CATEGORY)
            .userEditable(true)
            .description("Timezone the subject's times are rendered and scheduled in")
            .build();

    /**
     * When not to disturb the subject, interpreted in {@link #TIMEZONE}.
     *
     * <p>One setting rather than three; see {@link QuietHours} for why a layered engine cannot
     * resolve the parts independently.
     */
    public static final SettingDefinition<QuietHours> QUIET_HOURS = SettingDefinition
            .of("user.notifications.quiet-hours", QuietHours.class)
            .defaultValue(QuietHours.disabled())
            .category(NOTIFICATIONS_CATEGORY)
            .userEditable(true)
            .jsonEncoded(true)
            .description("Window during which the subject should not be disturbed")
            .build();

    /** How often batched notifications should be delivered. */
    public static final SettingDefinition<DigestPreference> DIGEST = SettingDefinition
            .of("user.notifications.digest", DigestPreference.class)
            .defaultValue(DigestPreference.IMMEDIATE)
            .category(NOTIFICATIONS_CATEGORY)
            .userEditable(true)
            .description("How often batched notifications are delivered")
            .build();

    private WellKnownSettings() {
    }

    /**
     * An opt-out for one notification category on one channel.
     *
     * <p>A factory rather than a list of constants, because which categories exist is the consuming
     * service's business and not this module's. A notification service declares one of these per
     * category it actually sends, and contributes them as a source; a module that shipped a fixed
     * list would either be missing that service's categories or be full of ones nobody sends.
     *
     * <p>The default is {@code false} - not opted out - so that a category nobody has expressed an
     * opinion about is delivered. The opposite default would make adding a category silently mute it
     * for every existing user.
     *
     * <p>Calling this twice with the same arguments produces two equal definitions, which the
     * registry accepts: definitions are compared by key and then structurally, precisely so a
     * factory-built definition can appear in more than one source.
     *
     * @param category the notification category, lowercase
     * @param channel  the delivery channel, lowercase
     */
    public static SettingDefinition<Boolean> channelOptOut(String category, String channel) {
        return SettingDefinition
                .of(optOutKey(category, channel), Boolean.class)
                .defaultValue(Boolean.FALSE)
                .category(NOTIFICATIONS_CATEGORY)
                .userEditable(true)
                .description("Whether the subject has opted out of " + category + " on " + channel)
                .build();
    }

    /**
     * The key {@link #channelOptOut} produces, for code that has to name one without building a
     * definition - a migration, a report.
     */
    public static String optOutKey(String category, String channel) {
        return "user.notifications.opt-out." + segment(category, "category") + "." + segment(channel, "channel");
    }

    /**
     * The four settings that are the same everywhere, as a ready-made source.
     *
     * <p>The per-category opt-outs are not here: only the consuming service knows its categories.
     * Combine them with {@code SettingDefinitionSource.of(...)} over a list built from both.
     */
    public static SettingDefinitionSource source() {
        return SettingDefinitionSource.of(LOCALE, TIMEZONE, QUIET_HOURS, DIGEST);
    }

    /**
     * Lowercases and checks one key segment.
     *
     * <p>Checked here rather than left to the definition's own key validation so the message names
     * the offending argument. "Setting key must be lowercase dot-separated segments, was
     * user.notifications.opt-out.Billing.email" is a puzzle; "category must not contain a dot" is an
     * instruction.
     */
    private static String segment(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A notification opt-out needs a " + what);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "A notification opt-out " + what + " must not contain a dot, because it becomes one"
                            + " segment of the setting key: " + value);
        }
        return normalized;
    }

    /** Every core definition, for a service that wants to filter or extend the list. */
    public static List<SettingDefinition<?>> core() {
        return List.of(LOCALE, TIMEZONE, QUIET_HOURS, DIGEST);
    }
}
