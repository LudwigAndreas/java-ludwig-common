package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.usersettings.admin.web.AdminSettingsController;
import ru.ludwigandreas.usersettings.admin.web.SettingsBackfillController;
import ru.ludwigandreas.usersettings.config.UserSettingsAdminAutoConfiguration;

/**
 * The {@code settings} area fails closed: without the administrative surface there is no administrative endpoint,
 * even with the shipped endpoints and the backfill switched on. The security starter is a hard dependency of this
 * module, so only the missing surface is a case here.
 */
class UserSettingsAdminAutoConfigurationTest {

    @Test
    @DisplayName("without the administrative surface, the module starts with no administrative endpoint")
    void withoutTheSurface() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(UserSettingsAdminAutoConfiguration.class))
                .withPropertyValues("ludwig.user-settings.web.enabled=true",
                        "ludwig.user-settings.web.backfill-enabled=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AdminSettingsController.class)
                        .doesNotHaveBean(SettingsBackfillController.class));
    }
}
