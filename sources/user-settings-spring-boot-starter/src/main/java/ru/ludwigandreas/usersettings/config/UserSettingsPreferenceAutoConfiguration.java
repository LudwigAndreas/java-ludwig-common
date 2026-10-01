package ru.ludwigandreas.usersettings.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.preference.StoredUserPreferenceSource;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;

/**
 * Contributes the caller's stored locale and timezone to {@code web-core}'s preference chain.
 *
 * <h2>Why this is its own autoconfiguration</h2>
 *
 * <p>Because it is the only part of this module that is useful to a service which does not want the
 * rest of it, and because its conditions are different from
 * {@link UserSettingsAutoConfiguration}'s. That class requires an {@code EntityManager} and a
 * settings mode; this requires a {@link SettingsLookup} bean, whichever of the two modes produced
 * it, and {@code web-core}'s SPI on the classpath. Folding it in would have tied the contribution to
 * conditions it does not actually need.
 *
 * <p>Ordered {@code after} that class so {@code @ConditionalOnBean(SettingsLookup.class)} has
 * something to look at: a {@code @ConditionalOnBean} is evaluated against the beans registered so
 * far, which makes autoconfiguration order a correctness concern rather than a preference.
 *
 * <h2>Registered unconditionally, abstaining explicitly</h2>
 *
 * <p>The source is registered whenever there is a lookup, even in a service that has not declared
 * {@code WellKnownSettings.LOCALE} or {@code TIMEZONE}. It then abstains per dimension and says so
 * in its {@code sourceName()}, which the startup line prints. That is the opposite of the obvious
 * design - a {@code @Conditional} that keeps the bean out - and it is chosen deliberately: a
 * condition cannot inspect the contents of a {@link SettingDefinitionRegistry} that has not been
 * built yet, and a bean that is silently absent is indistinguishable from a deliberate choice.
 * Abstaining out loud is the only form of this that a person debugging "my saved timezone does
 * nothing" can actually see.
 */
@AutoConfiguration(after = UserSettingsAutoConfiguration.class)
@ConditionalOnClass(UserPreferenceSource.class)
@ConditionalOnBean(SettingsLookup.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.preferences", name = "enabled", matchIfMissing = true)
public class UserSettingsPreferenceAutoConfiguration {

    /**
     * The stored source, first in {@code web-core}'s chain.
     *
     * @param settings the module's read side
     * @param registry asked which well-known definitions this service declared
     * @return the source
     */
    @Bean
    @ConditionalOnMissingBean(StoredUserPreferenceSource.class)
    public StoredUserPreferenceSource storedUserPreferenceSource(SettingsLookup settings,
                                                                 SettingDefinitionRegistry registry) {
        return new StoredUserPreferenceSource(settings, registry);
    }
}
