package ru.ludwigandreas.notification.service.preference.usersettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.OptOutMatrix;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;

/**
 * The settings this service reads, declared as the compile-time constants
 * {@code user-settings-spring-boot-starter} requires.
 *
 * <h2>Only compiled in when the module is</h2>
 *
 * <p>This class lives in the adapter package and mentions {@code SettingDefinition} in its
 * signature, so it loads only where {@code user-settings-spring-boot-starter} is on the classpath.
 * Nothing on the dispatch path refers to it - the path asks {@code RecipientPreferences}, which is
 * built from this service's own types.
 *
 * <h2>This service does not own any of them</h2>
 *
 * <p>Locale, timezone, quiet hours, the digest preference and the per-category opt-outs are the
 * user's preferences and live in the account service's database. This service keeps a read-only
 * replica of them - see {@code UserSettingsProjection} in the README - and declaring the definitions
 * here is how it says which ones it intends to read, not a claim to own them.
 *
 * <p>The account service must declare the same keys, because it is the one that stores them. That is
 * the contract between the two, and it is a contract in code on both sides rather than a string in a
 * table on one: a key this service reads and the owner never stores simply resolves to its default,
 * which is exactly the failure a shared constants class is meant to prevent. The well-known
 * definitions come from the starter for that reason; only the opt-outs, whose categories are this
 * platform's own, are built here.
 *
 * <h2>Why the opt-outs are built from configuration</h2>
 *
 * <p>Which notification categories exist is a property of the platform, not of this module, so the
 * starter ships a factory rather than a list. The declinable categories are configured
 * ({@code ludwig.notification.preferences.declinable-categories}) and each one becomes a definition
 * per channel, plus a wildcard row per channel so a recipient can decline everything at once.
 *
 * <p>A category that is not configured cannot be declined per-category, only through the wildcard.
 * That is deliberate: a marketing category nobody has declared is one nobody has thought about, and
 * the honest behaviour is that the blanket opt-out still covers it.
 */
public final class NotificationSettings {

    /**
     * The pseudo-category meaning "every declinable category on this channel".
     *
     * <p>A category name rather than a separate key shape, so it goes through exactly the same
     * definition factory, the same storage and the same layered resolution as a real one.
     *
     * <p>Aliased from {@link OptOutMatrix#ALL_CATEGORIES} rather than declared twice. The dispatch
     * path asks the blanket question through the matrix and this adapter answers it out of the
     * settings replica, so the two names have to be the same string - and two literals that have to
     * agree eventually do not.
     */
    public static final String ALL_CATEGORIES = OptOutMatrix.ALL_CATEGORIES;

    private NotificationSettings() {
    }

    /** The opt-out for one category on one channel. */
    public static SettingDefinition<Boolean> optOut(String category, ChannelType channel) {
        return WellKnownSettings.channelOptOut(category, channel.name().toLowerCase(Locale.ROOT));
    }

    /** The blanket opt-out for one channel. */
    public static SettingDefinition<Boolean> blanketOptOut(ChannelType channel) {
        return optOut(ALL_CATEGORIES, channel);
    }

    /**
     * Everything this service reads: the platform's well-known settings plus an opt-out per
     * declinable category per channel, and a blanket opt-out per channel.
     *
     * @param declinableCategories the categories a recipient may decline individually
     */
    public static SettingDefinitionSource source(List<String> declinableCategories) {
        List<SettingDefinition<?>> declared = new ArrayList<>(WellKnownSettings.core());
        for (ChannelType channel : ChannelType.values()) {
            declared.add(blanketOptOut(channel));
            for (String category : declinableCategories) {
                declared.add(optOut(category, channel));
            }
        }
        return SettingDefinitionSource.of(declared);
    }
}
