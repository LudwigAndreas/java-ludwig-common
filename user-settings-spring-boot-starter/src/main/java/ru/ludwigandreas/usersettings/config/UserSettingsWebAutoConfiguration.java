package ru.ludwigandreas.usersettings.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillService;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.web.AdminSettingsController;
import ru.ludwigandreas.usersettings.web.MeSettingsController;
import ru.ludwigandreas.usersettings.web.SettingsBackfillController;
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
 * <p>The administrative controller additionally requires a {@link SettingsWriter}, which exists only
 * in owner mode - so a projection gets the self-service endpoints and not the ones that would have
 * to refuse every write anyway.
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

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SettingsWriter.class)
    public AdminSettingsController adminSettingsController(SettingsLookup lookup,
                                                            SettingsWriter writer,
                                                            ConsentService consents,
                                                            SettingsResponseRenderer mapper,
                                                            SettingDefinitionRegistry registry,
                                                            SettingsTenantResolver tenantResolver) {
        return new AdminSettingsController(lookup, writer, consents, mapper, registry, tenantResolver);
    }

    /**
     * Behind a switch of its own, on top of the two this class already requires.
     *
     * <p>Every other endpoint here acts on one subject. This one republishes the whole tenant, so the
     * cost of leaving it mounted by accident is measured in broker traffic and projection load rather
     * than in one wrong answer - and the operator who needs it is reading a runbook anyway, which is
     * the right moment to also set a flag.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SettingsBackfillService.class)
    @ConditionalOnProperty(prefix = "ludwig.user-settings.web", name = "backfill-enabled",
            havingValue = "true")
    public SettingsBackfillController settingsBackfillController(SettingsBackfillService backfill,
                                                                  SettingsAccessPolicy accessPolicy,
                                                                  SettingsTenantResolver tenantResolver) {
        return new SettingsBackfillController(backfill, accessPolicy, tenantResolver);
    }
}
