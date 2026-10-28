package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.identity.admin.web.IdentityAdminController;
import ru.ludwigandreas.identity.config.IdentityAdminAutoConfiguration;

/**
 * The {@code identity} area fails closed: without the security starter's administrative surface there is no
 * endpoint. The security starter is a hard dependency of this module, so only the missing surface is a case here.
 */
class IdentityAdminAutoConfigurationTest {

    @Test
    @DisplayName("without the administrative surface, the module starts with no administrative endpoint")
    void withoutTheSurface() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdentityAdminAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(IdentityAdminController.class));
    }
}
