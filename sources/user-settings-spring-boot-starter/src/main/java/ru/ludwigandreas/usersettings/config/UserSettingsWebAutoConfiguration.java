package ru.ludwigandreas.usersettings.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.web.MeSettingsController;
import ru.ludwigandreas.usersettings.web.SettingsResponseRenderer;
import ru.ludwigandreas.usersettings.write.RawSettingWriter;

/**
 * The shipped REST endpoints, off by default.
 *
 * <p>A service has to be able to own its own API shape - its paths, its DTOs, its versioning, its
 * OpenAPI groups - and a starter that mounted endpoints without being asked would be deciding that
 * for it. What this is for is the service that has no opinion yet and wants something working on day
 * one; turning it off later and writing its own controllers changes no other behaviour.
 *
 * <p>The administrative controllers are {@link UserSettingsAdminAutoConfiguration}'s, because they
 * exist only where the administrative surface does.
 */
@AutoConfiguration(after = {UserSettingsOwnerAutoConfiguration.class,
        UserSettingsProjectionAutoConfiguration.class})
@ConditionalOnClass(RestController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ludwig.user-settings.web", name = "enabled", havingValue = "true")
public class UserSettingsWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SettingsResponseRenderer settingsResponseRenderer(SettingDefinitionRegistry registry) {
        return new SettingsResponseRenderer(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MeSettingsController meSettingsController(SettingsLookup lookup,
                                                      ConsentService consents,
                                                      SettingsResponseRenderer mapper,
                                                      RawSettingWriter writer) {
        return new MeSettingsController(lookup, consents, mapper, writer);
    }
}
