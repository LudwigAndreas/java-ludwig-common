package ru.ludwigandreas.usersettings.unit;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingValidator;

/**
 * The definitions the tests are written against.
 *
 * <p>Deliberately a service-shaped declaration rather than a fixture built per test: the whole point
 * of definitions being compile-time constants is that this is what a consuming service's own file
 * looks like, and a test that declared them inline would be exercising a shape nobody ships.
 */
final class TestSettings {

    static final SettingDefinition<ZoneId> TIMEZONE = SettingDefinition
            .of("user.timezone", ZoneId.class)
            .defaultValue(ZoneId.of("UTC"))
            .category("locale")
            .userEditable(true)
            .build();

    static final SettingDefinition<Locale> LOCALE = SettingDefinition
            .of("user.locale", Locale.class)
            .defaultValue(Locale.ENGLISH)
            .category("locale")
            .userEditable(true)
            .build();

    static final SettingDefinition<Integer> PAGE_SIZE = SettingDefinition
            .of("ui.page-size", Integer.class)
            .defaultValue(25)
            .category("ui")
            .userEditable(true)
            .validator(SettingValidator.range(1, 100))
            .build();

    /** Not user-editable, to exercise the write refusal. */
    static final SettingDefinition<Boolean> BETA_FEATURES = SettingDefinition
            .of("ui.beta-features", Boolean.class)
            .defaultValue(Boolean.FALSE)
            .category("ui")
            .userEditable(false)
            .build();

    /** PII-flagged, to exercise redaction in the audit trail and in administrative responses. */
    static final SettingDefinition<String> CONTACT_NOTE = SettingDefinition
            .of("user.contact-note", String.class)
            .category("profile")
            .userEditable(true)
            .pii(true)
            .validator(SettingValidator.maxLength(200))
            .build();

    static SettingDefinitionSource source() {
        return SettingDefinitionSource.of(
                List.of(TIMEZONE, LOCALE, PAGE_SIZE, BETA_FEATURES, CONTACT_NOTE));
    }

    private TestSettings() {
    }
}
