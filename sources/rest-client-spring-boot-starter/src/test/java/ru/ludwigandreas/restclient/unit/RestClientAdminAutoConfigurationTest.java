package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration.ReplicaControls;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.restclient.admin.RestClientAdminOperations;
import ru.ludwigandreas.restclient.admin.service.RestClientBreakerControl;
import ru.ludwigandreas.restclient.config.RestClientAdminAutoConfiguration;
import ru.ludwigandreas.restclient.config.RestClientAuthAutoConfiguration;
import ru.ludwigandreas.restclient.config.RestClientAutoConfiguration;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;

/** The restclient area fails closed: without job-core's channel or the administrative surface it does not exist. */
class RestClientAdminAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, AuditCoreAutoConfiguration.class,
                    RestClientAutoConfiguration.class, RestClientAuthAutoConfiguration.class,
                    RestClientAdminAutoConfiguration.class));

    private ApplicationContextRunner withSurfaceAndChannel() {
        return runner.withBean(AdminSurfaceGuard.class, () -> org.mockito.Mockito.mock(AdminSurfaceGuard.class))
                .withBean(ReplicaControls.class, () -> new ReplicaControls(Map.of()));
    }

    @Test
    @DisplayName("with the surface and the replica channel, the area and its controls are registered")
    void presentWithItsPrerequisites() {
        withSurfaceAndChannel().run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(RestClientBreakerControl.class).hasSingleBean(RestClientAdminOperations.class));
    }

    @Test
    @DisplayName("without the replica channel, without job-core, or without the surface, nothing is registered")
    void absentWithoutItsPrerequisites() {
        runner.withBean(AdminSurfaceGuard.class, () -> org.mockito.Mockito.mock(AdminSurfaceGuard.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RestClientBreakerControl.class));
        withSurfaceAndChannel().withClassLoader(new FilteredClassLoader(ReplicaControl.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RestClientBreakerControl.class));
        runner.withClassLoader(new FilteredClassLoader(AdminSurfaceGuard.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RestClientBreakerControl.class));
    }
}
