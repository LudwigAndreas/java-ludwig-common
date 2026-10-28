package ru.ludwigandreas.job.core.unit;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.job.core.admin.web.JobAdminController;
import ru.ludwigandreas.job.core.config.JobAdminAutoConfiguration;

/**
 * The {@code jobs} area fails closed: without the security starter's administrative surface there is no endpoint.
 */
class JobAdminAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobAdminAutoConfiguration.class))
            .withBean(DataSource.class, () -> Mockito.mock(DataSource.class));

    @Test
    @DisplayName("without the security starter on the classpath, the module starts with no administrative endpoint")
    void withoutTheSecurityStarter() {
        runner.withClassLoader(
                        new FilteredClassLoader(FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.security")))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JobAdminController.class));
    }

    @Test
    @DisplayName("with the security starter present but no administrative surface, there is still no endpoint")
    void withoutTheSurface() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JobAdminController.class));
    }
}
