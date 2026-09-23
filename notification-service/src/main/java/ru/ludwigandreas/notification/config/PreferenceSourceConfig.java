package ru.ludwigandreas.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.notification.service.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.notification.service.preference.RecipientPreferenceSource;

/**
 * The preference source that is always available, whatever else is deployed.
 *
 * <p>This service reads five things about a recipient that belong to the recipient rather than to
 * it - locale, timezone, quiet hours, digest cadence and per-category opt-outs - and it deliberately
 * stores none of them. Where the platform runs a preference store, an adapter reads it; where it does
 * not, this bean answers "nothing stored" and the configured defaults apply.
 *
 * <p>Registered unconditionally, and overridden rather than replaced: when the user-settings module
 * is present and permitted, {@link UserSettingsPreferenceConfig} contributes a {@code @Primary}
 * source and every injection point picks that one up. Two beans of one type with a primary is
 * Spring's own answer to "an optional implementation that outranks the default", and the alternative
 * - a {@code @ConditionalOnMissingBean} in a second application configuration class - depends on the
 * order Spring happens to parse configuration classes in, which is not a contract. The two beans are
 * also why {@link RecipientPreferenceSource#describe()} exists: the startup log says which one won.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class PreferenceSourceConfig {

    @Bean
    public ConfiguredPreferenceSource configuredPreferenceSource() {
        log.debug("Configuration-default preference source registered");
        return new ConfiguredPreferenceSource();
    }
}
