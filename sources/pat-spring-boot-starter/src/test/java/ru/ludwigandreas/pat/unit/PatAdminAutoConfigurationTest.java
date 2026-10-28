package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.pat.admin.web.PatAdminController;
import ru.ludwigandreas.pat.autoconfigure.PatAdminAutoConfiguration;

/**
 * The {@code pat} area fails closed: without the administrative surface there is no administrative endpoint. The
 * security starter is a hard dependency of this module, so only the missing surface is a case here.
 */
class PatAdminAutoConfigurationTest {

    @Test
    @DisplayName("without the administrative surface, the module starts with no administrative endpoint")
    void withoutTheSurface() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PatAdminAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PatAdminController.class));
    }
}
