package ru.ludwigandreas.usersettings.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.usersettings.admin.SettingsAdminOperations;
import ru.ludwigandreas.usersettings.admin.web.AdminSettingsController;
import ru.ludwigandreas.usersettings.admin.web.SettingsBackfillController;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillService;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.web.SettingsResponseRenderer;

/**
 * The {@code settings} administrative area, registered only where the administrative surface exists and the
 * shipped endpoints are on.
 *
 * <p>The controllers additionally require a {@link SettingsWriter}, which exists only in owner mode - so a
 * projection does not mount endpoints that would have to refuse every write anyway.
 */
@AutoConfiguration(after = {UserSettingsWebAutoConfiguration.class, UserSettingsOwnerAutoConfiguration.class},
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnProperty(prefix = "ludwig.user-settings.web", name = "enabled", havingValue = "true")
@ConditionalOnBean({AdminOperationCatalog.class, SettingsResponseRenderer.class, SettingsWriter.class})
public class UserSettingsAdminAutoConfiguration {

    @Bean
    public SettingsAdminOperations ludwigSettingsAdminOperations() {
        return new SettingsAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminSettingsController adminSettingsController(SettingsLookup lookup,
                                                            SettingsWriter writer,
                                                            ConsentService consents,
                                                            SettingsResponseRenderer mapper,
                                                            SettingDefinitionRegistry registry,
                                                            SettingsTenantResolver tenantResolver) {
        return new AdminSettingsController(lookup, writer, consents, mapper, registry, tenantResolver);
    }

    /**
     * Behind a switch of its own, on top of the conditions above.
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
                                                                  SettingsTenantResolver tenantResolver) {
        return new SettingsBackfillController(backfill, tenantResolver);
    }
}
