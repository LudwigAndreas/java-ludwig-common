package ru.ludwigandreas.hotreload.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import ru.ludwigandreas.hotreload.audit.AuditingSourceChangeListener;
import ru.ludwigandreas.hotreload.audit.HotReloadAuditLogger;
import ru.ludwigandreas.hotreload.audit.Slf4jHotReloadAuditLogger;
import ru.ludwigandreas.hotreload.binding.ConfigurationBinder;
import ru.ludwigandreas.hotreload.binding.HotReloadTypedConfigFactory;
import ru.ludwigandreas.hotreload.core.FileBackedSource;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.hotreload.core.watch.FileResourceWatcher;
import ru.ludwigandreas.hotreload.metrics.HotReloadMetrics;
import ru.ludwigandreas.hotreload.metrics.MetricsSourceChangeListener;
import ru.ludwigandreas.hotreload.metrics.MicrometerHotReloadMetrics;
import ru.ludwigandreas.hotreload.metrics.NoopHotReloadMetrics;
import ru.ludwigandreas.hotreload.properties.PropertyFileSource;
import ru.ludwigandreas.hotreload.propertysource.EnvironmentPropertySourceBridge;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;

/**
 * Core autoconfiguration: the reload engine ({@link SourceReloadCoordinator}), the file watcher for
 * {@code ludwig.hotreload.files}, typed/validated configuration ({@link HotReloadTypedConfigFactory}),
 * and the bridge that pushes reloaded content into the {@code Environment}. Vault and FreeMarker support
 * live in their own conditional autoconfigurations ({@link HotReloadVaultAutoConfiguration}, {@link
 * HotReloadFreemarkerAutoConfiguration}) so this module has no hard dependency on either.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.hotreload", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(HotReloadProperties.class)
public class HotReloadAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SourceReloadCoordinator hotReloadSourceReloadCoordinator() {
        return new SourceReloadCoordinator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigurationBinder hotReloadConfigurationBinder(ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable(HotReloadAutoConfiguration::defaultValidatorOrNull);
        return new ConfigurationBinder(validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public HotReloadTypedConfigFactory hotReloadTypedConfigFactory(ConfigurableEnvironment environment,
                                                                     ConfigurationBinder binder,
                                                                     SourceReloadCoordinator coordinator) {
        return new HotReloadTypedConfigFactory(environment, binder, coordinator);
    }

    @Bean
    @ConditionalOnMissingBean
    public EnvironmentPropertySourceBridge hotReloadEnvironmentPropertySourceBridge(ConfigurableEnvironment environment,
                                                                                      ApplicationEventPublisher eventPublisher,
                                                                                      SourceReloadCoordinator coordinator) {
        EnvironmentPropertySourceBridge bridge = new EnvironmentPropertySourceBridge(environment, eventPublisher);
        coordinator.addListener(bridge);
        return bridge;
    }

    @Bean
    @ConditionalOnMissingBean(name = "hotReloadFileWatcherLifecycle")
    public ResourceWatcherLifecycle hotReloadFileWatcherLifecycle(HotReloadProperties properties,
                                                                    SourceReloadCoordinator coordinator) {
        List<FileBackedSource> sources = properties.getFiles().stream()
                .filter(file -> file.getPath() != null && !file.getPath().isBlank())
                .<FileBackedSource>map(file -> new PropertyFileSource(Path.of(file.getPath()), file.getKeyPrefix()))
                .toList();
        FileResourceWatcher watcher = new FileResourceWatcher(sources, coordinator, properties.getFileWatch().getDebounce());
        return new ResourceWatcherLifecycle(watcher);
    }

    @Bean
    @ConditionalOnMissingBean(HotReloadAuditLogger.class)
    public HotReloadAuditLogger hotReloadAuditLogger() {
        return new Slf4jHotReloadAuditLogger();
    }

    @Bean
    @ConditionalOnMissingBean(AuditingSourceChangeListener.class)
    @ConditionalOnProperty(prefix = "ludwig.hotreload.audit", name = "enabled", matchIfMissing = true)
    public AuditingSourceChangeListener hotReloadAuditingSourceChangeListener(HotReloadProperties properties,
                                                                                HotReloadAuditLogger auditLogger,
                                                                                SourceReloadCoordinator coordinator) {
        AuditingSourceChangeListener listener = new AuditingSourceChangeListener(auditLogger, resolveActor(properties));
        coordinator.addListener(listener);
        return listener;
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ludwig.hotreload.metrics", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean(HotReloadMetrics.class)
    public HotReloadMetrics micrometerHotReloadMetrics(MeterRegistry registry) {
        return new MicrometerHotReloadMetrics(registry);
    }

    /** Registered whenever the bean above didn't fire (Micrometer absent/disabled/no registry) - keeps callers null-check-free. */
    @Bean
    @ConditionalOnMissingBean(HotReloadMetrics.class)
    public HotReloadMetrics noopHotReloadMetrics() {
        return new NoopHotReloadMetrics();
    }

    @Bean
    @ConditionalOnMissingBean(MetricsSourceChangeListener.class)
    public MetricsSourceChangeListener hotReloadMetricsSourceChangeListener(HotReloadMetrics metrics,
                                                                              SourceReloadCoordinator coordinator) {
        MetricsSourceChangeListener listener = new MetricsSourceChangeListener(metrics);
        coordinator.addListener(listener);
        return listener;
    }

    private static Validator defaultValidatorOrNull() {
        try {
            return Validation.buildDefaultValidatorFactory().getValidator();
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveActor(HotReloadProperties properties) {
        String configured = properties.getAudit().getActor();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
