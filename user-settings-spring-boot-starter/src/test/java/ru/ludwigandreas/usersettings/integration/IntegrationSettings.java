package ru.ludwigandreas.usersettings.integration;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingValidator;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;

/**
 * The definitions the integration tests run against: the shared well-known ones plus a couple of
 * service-specific ones, which is the shape a real consuming service has.
 */
public final class IntegrationSettings {

    static final SettingDefinition<ZoneId> TIMEZONE = WellKnownSettings.TIMEZONE;
    static final SettingDefinition<Locale> LOCALE = WellKnownSettings.LOCALE;

    static final SettingDefinition<Integer> PAGE_SIZE = SettingDefinition
            .of("ui.page-size", Integer.class)
            .defaultValue(25)
            .category("ui")
            .userEditable(true)
            .validator(SettingValidator.range(1, 100))
            .build();

    static final SettingDefinition<Boolean> BETA_FEATURES = SettingDefinition
            .of("ui.beta-features", Boolean.class)
            .defaultValue(Boolean.FALSE)
            .category("ui")
            .userEditable(false)
            .build();

    static final SettingDefinition<String> CONTACT_NOTE = SettingDefinition
            .of("user.contact-note", String.class)
            .category("profile")
            .userEditable(true)
            .pii(true)
            .build();

    static SettingDefinitionSource source() {
        List<SettingDefinition<?>> declared = List.of(
                TIMEZONE, LOCALE, WellKnownSettings.QUIET_HOURS, WellKnownSettings.DIGEST,
                PAGE_SIZE, BETA_FEATURES, CONTACT_NOTE,
                WellKnownSettings.channelOptOut("billing", "email"));
        return SettingDefinitionSource.of(declared);
    }

    private IntegrationSettings() {
    }
}
