package ru.ludwigandreas.hotreload.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.hotreload.admin.HotReloadAdminOperations;
import ru.ludwigandreas.hotreload.admin.service.HotReloadAdminService;
import ru.ludwigandreas.hotreload.admin.service.HotReloadReloadControl;
import ru.ludwigandreas.hotreload.admin.web.HotReloadAdminController;
import ru.ludwigandreas.hotreload.core.ReloadableSourceRegistry;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code hotreload} area and the {@code hotreloadreload} control, where the administrative surface and
 * job-core's replica-control channel both run. Without either nothing here exists, and the starter is exactly what
 * it was.
 */
@AutoConfiguration(after = HotReloadAutoConfiguration.class,
        afterName = {"ru.ludwigandreas.security.config.AdminAutoConfiguration",
            "ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration"})
@ConditionalOnClass(name = {"ru.ludwigandreas.security.admin.AdminSurfaceGuard",
    "ru.ludwigandreas.job.core.control.ReplicaControl", "ru.ludwigandreas.webcore.problem.ProblemMessageBundle"})
@ConditionalOnBean(type = {"ru.ludwigandreas.hotreload.core.ReloadableSourceRegistry",
    "ru.ludwigandreas.security.admin.AdminSurfaceGuard",
    "ru.ludwigandreas.job.core.config.ReplicaControlAutoConfiguration$ReplicaControls"})
public class HotReloadAdminAutoConfiguration {

    @Bean
    public HotReloadAdminOperations ludwigHotReloadAdminOperations() {
        return new HotReloadAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigHotReloadAdminMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-hotreload-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    public HotReloadAdminService ludwigHotReloadAdminService(ReloadableSourceRegistry registry,
                                                             SourceReloadCoordinator coordinator) {
        return new HotReloadAdminService(registry, coordinator);
    }

    @Bean
    @ConditionalOnMissingBean
    public HotReloadReloadControl ludwigHotReloadReloadControl(ReloadableSourceRegistry registry) {
        return new HotReloadReloadControl(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public HotReloadAdminController ludwigHotReloadAdminController(HotReloadAdminService service) {
        return new HotReloadAdminController(service);
    }
}
