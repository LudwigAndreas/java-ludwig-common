package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.fileaction.admin.web.FileActionAdminController;
import ru.ludwigandreas.fileaction.config.FileActionAdminAutoConfiguration;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The {@code fileactions} area fails closed: without the security starter, or without its administrative surface,
 * there is no endpoint and the module still starts.
 */
class FileActionAdminAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileActionAdminAutoConfiguration.class));

    @Test
    @DisplayName("without the administrative surface, the module starts with no administrative endpoint")
    void withoutTheSurface() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FileActionAdminController.class));
    }

    @Test
    @DisplayName("without the security starter on the classpath, the module starts with no administrative endpoint")
    void withoutTheSecurityStarter() {
        runner.withClassLoader(new FilteredClassLoader(AdminOperationCatalog.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(FileActionAdminController.class));
    }
}
