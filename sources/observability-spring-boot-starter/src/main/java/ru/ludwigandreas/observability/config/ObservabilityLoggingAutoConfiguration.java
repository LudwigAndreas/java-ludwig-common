package ru.ludwigandreas.observability.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.observability.logging.LogLevelReloader;

/**
 * Connects hot-reloaded configuration to the running logging system, so log levels can be changed
 * without a restart.
 *
 * <p>The JSON encoder is deliberately not configured here - it is installed far earlier, by
 * {@code JsonLoggingInitializer}, because by the time autoconfiguration runs a service has already
 * written its startup logs in the old format. See that class for the reasoning.
 *
 * <p>Everything in here is conditional on the hot-reload starter being present. It is an optional
 * dependency, and a service that has not adopted it simply keeps Spring Boot's
 * {@code /actuator/loggers} endpoint as its runtime control.
 */
@AutoConfiguration(afterName = "ru.ludwigandreas.hotreload.config.HotReloadAutoConfiguration")
@ConditionalOnClass(SourceReloadCoordinator.class)
@ConditionalOnProperty(
        name = {"ludwig.observability.enabled", "ludwig.observability.logging.levels.hot-reload-enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityLoggingAutoConfiguration {

    /**
     * Registers the reloader with the hot-reload coordinator.
     *
     * <p>Registration happens inside the bean method, matching how the hot-reload starter wires its
     * own listeners: the coordinator holds a plain listener list rather than scanning the context,
     * so a listener that is only declared as a bean is never called.
     *
     * <p>{@code @ConditionalOnBean(SourceReloadCoordinator.class)} as well as
     * {@code @ConditionalOnClass}, because the hot-reload starter can be on the classpath with
     * {@code ludwig.hotreload.enabled=false}, in which case no coordinator bean exists and this
     * would fail to inject one.
     */
    @Bean
    @ConditionalOnBean(SourceReloadCoordinator.class)
    @ConditionalOnMissingBean
    public LogLevelReloader ludwigLogLevelReloader(LoggingSystem loggingSystem,
            SourceReloadCoordinator coordinator, ObservabilityProperties properties) {
        LogLevelReloader reloader = new LogLevelReloader(
                loggingSystem, properties.getLogging().getLevels().isRevertOnRemoval());
        coordinator.addListener(reloader);
        return reloader;
    }
}
