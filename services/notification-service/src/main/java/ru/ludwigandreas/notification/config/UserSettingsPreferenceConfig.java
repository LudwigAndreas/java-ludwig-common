package ru.ludwigandreas.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import ru.ludwigandreas.notification.service.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.notification.service.preference.RecipientPreferenceSource;
import ru.ludwigandreas.notification.service.preference.usersettings.NotificationSettings;
import ru.ludwigandreas.notification.service.preference.usersettings.UserSettingsPreferenceSource;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingsLookup;

/**
 * Wires recipient preferences to {@code user-settings-spring-boot-starter}, when it is there.
 *
 * <h2>Three independent switches, and all three have to be on</h2>
 *
 * <p><b>The module on the classpath.</b> {@code @ConditionalOnClass} skips this whole class
 * otherwise, which is what makes the dependency genuinely optional: the class is read as bytecode
 * and never loaded, so neither the {@code SettingDefinitionSource} return type below nor
 * {@link UserSettingsPreferenceSource} has to resolve. The service's POM declares the dependency as
 * {@code provided} for exactly this reason - see the comment there.
 *
 * <p><b>The deployment permitting it.</b> {@link PreferenceSourceCondition} reads
 * {@code ludwig.notification.preferences.source}, so a deployment that has the module for another
 * reason can still be told to resolve preferences from configuration.
 *
 * <p><b>The module having actually started.</b> The module contributes a {@link SettingsLookup} only
 * once a mode is configured ({@code ludwig.user-settings.owner.enabled} or
 * {@code .projection.enabled}); having the jar is not the same as having a store. That one cannot be
 * a condition - the module's beans come from an autoconfiguration, which is registered after this
 * application configuration is parsed, so {@code @ConditionalOnBean} here would always see nothing.
 * It is therefore resolved where it is answerable: at bean creation, through an
 * {@link ObjectProvider}.
 *
 * <h2>What happens when the third one is off</h2>
 *
 * <p>The primary bean falls back to the configuration-default source and logs a warning. Not a
 * startup failure, because the combination is legitimate during a migration - the dependency lands in
 * one release and the store is switched on in the next - and because a notification service that
 * refuses to start is worse than one running on default preferences.
 *
 * <p>{@code source: USER_SETTINGS} is the way to say that is not acceptable here:
 * {@code NotificationConfigurationValidator} turns the same situation into a refusal to start.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SettingsLookup.class)
@Conditional(PreferenceSourceCondition.class)
public class UserSettingsPreferenceConfig {

    /**
     * Declares which settings this service reads.
     *
     * <p>The module builds its registry from beans of this type and validates the lot before the
     * context finishes starting, so a category configured with a name that is not a legal setting key,
     * or a definition that collides with another, stops the deploy rather than the first delivery.
     *
     * <p>This service declares them; the owning service <em>owns</em> them. Both have to declare the
     * same keys - see {@link NotificationSettings} - and a key declared here but never stored there
     * simply resolves to its default, which is the safe direction: an opt-out nobody has expressed is
     * "not opted out".
     */
    @Bean
    public SettingDefinitionSource notificationSettingDefinitions(NotificationProperties properties) {
        return NotificationSettings.source(properties.getPreferences().getDeclinableCategories());
    }

    @Bean
    @Primary
    public RecipientPreferenceSource userSettingsPreferenceSource(
            ObjectProvider<SettingsLookup> lookup, ConfiguredPreferenceSource fallback) {

        SettingsLookup resolved = lookup.getIfAvailable();
        if (resolved == null) {
            log.warn("user-settings-spring-boot-starter is on the classpath but contributes no "
                    + "SettingsLookup, so no mode is enabled. Recipient preferences will come from "
                    + "ludwig.notification.preferences instead. Set ludwig.user-settings.owner.enabled "
                    + "or ludwig.user-settings.projection.enabled to read stored preferences, or set "
                    + "ludwig.notification.preferences.source=NONE to silence this.");
            return fallback;
        }

        UserSettingsPreferenceSource source = new UserSettingsPreferenceSource(resolved);
        log.info("Recipient preferences resolved from the {}", source.describe());
        return source;
    }
}
