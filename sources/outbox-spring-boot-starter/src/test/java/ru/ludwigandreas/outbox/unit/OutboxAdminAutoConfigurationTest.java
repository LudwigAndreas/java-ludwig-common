package ru.ludwigandreas.outbox.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.outbox.admin.web.OutboxAdminController;
import ru.ludwigandreas.outbox.config.OutboxAdminAutoConfiguration;

/**
 * The {@code outbox} area fails closed: without the security starter's administrative surface there is no endpoint.
 */
class OutboxAdminAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAdminAutoConfiguration.class));

    @Test
    @DisplayName("without the security starter on the classpath, the module starts with no administrative endpoint")
    void withoutTheSecurityStarter() {
        runner.withClassLoader(
                        new FilteredClassLoader(FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.security")))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxAdminController.class));
    }

    @Test
    @DisplayName("with the security starter present but no administrative surface, there is still no endpoint")
    void withoutTheSurface() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxAdminController.class));
    }
}
