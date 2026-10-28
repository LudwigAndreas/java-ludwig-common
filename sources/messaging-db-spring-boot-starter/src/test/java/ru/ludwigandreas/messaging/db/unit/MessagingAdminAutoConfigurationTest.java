package ru.ludwigandreas.messaging.db.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.messaging.db.admin.web.MessagingAdminController;
import ru.ludwigandreas.messaging.db.config.MessagingDbAdminAutoConfiguration;

/**
 * The {@code messaging} area fails closed: without the security starter's administrative surface there is no
 * endpoint.
 */
class MessagingAdminAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingDbAdminAutoConfiguration.class));

    @Test
    @DisplayName("without the security starter on the classpath, the module starts with no administrative endpoint")
    void withoutTheSecurityStarter() {
        runner.withClassLoader(
                        new FilteredClassLoader(FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.security")))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MessagingAdminController.class));
    }

    @Test
    @DisplayName("with the security starter present but no administrative surface, there is still no endpoint")
    void withoutTheSurface() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MessagingAdminController.class));
    }
}
