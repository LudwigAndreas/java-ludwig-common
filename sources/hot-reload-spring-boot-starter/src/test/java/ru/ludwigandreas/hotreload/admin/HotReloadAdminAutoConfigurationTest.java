package ru.ludwigandreas.hotreload.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.hotreload.admin.service.HotReloadReloadControl;
import ru.ludwigandreas.hotreload.config.HotReloadAdminAutoConfiguration;
import ru.ludwigandreas.hotreload.config.HotReloadAutoConfiguration;
import ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration.ReplicaControls;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;

/** The hotreload area fails closed: without job-core's channel or the administrative surface it does not exist. */
class HotReloadAdminAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HotReloadAutoConfiguration.class,
                    HotReloadAdminAutoConfiguration.class))
            .withPropertyValues("ludwig.hotreload.audit.enabled=false");

    private ApplicationContextRunner withSurfaceAndChannel() {
        return runner.withBean(AdminSurfaceGuard.class, () -> org.mockito.Mockito.mock(AdminSurfaceGuard.class))
                .withBean(ReplicaControls.class, () -> new ReplicaControls(Map.of()));
    }

    @Test
    @DisplayName("with the surface and the replica channel, the area and its control are registered")
    void presentWithItsPrerequisites() {
        withSurfaceAndChannel().run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(HotReloadReloadControl.class).hasSingleBean(HotReloadAdminOperations.class));
    }

    @Test
    @DisplayName("without the replica channel, without job-core, or without the surface, nothing is registered")
    void absentWithoutItsPrerequisites() {
        runner.withBean(AdminSurfaceGuard.class, () -> org.mockito.Mockito.mock(AdminSurfaceGuard.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(HotReloadReloadControl.class));
        withSurfaceAndChannel().withClassLoader(new FilteredClassLoader(ReplicaControl.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(HotReloadReloadControl.class));
        runner.withClassLoader(new FilteredClassLoader(AdminSurfaceGuard.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(HotReloadReloadControl.class));
    }
}
