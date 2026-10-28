package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.export.admin.web.ExportAdminController;
import ru.ludwigandreas.export.config.ExportAdminAutoConfiguration;

/**
 * The {@code exports} area fails closed: without the administrative surface there is no endpoint. The security
 * starter is a hard dependency of this module, so only the missing surface is a case here.
 */
class ExportAdminAutoConfigurationTest {

    @Test
    @DisplayName("without the administrative surface, the module starts with no administrative endpoint")
    void withoutTheSurface() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ExportAdminAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ExportAdminController.class));
    }
}
