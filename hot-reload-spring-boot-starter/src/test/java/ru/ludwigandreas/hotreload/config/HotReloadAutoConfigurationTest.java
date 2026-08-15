package ru.ludwigandreas.hotreload.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.hotreload.audit.AuditingSourceChangeListener;
import ru.ludwigandreas.hotreload.audit.HotReloadAuditLogger;
import ru.ludwigandreas.hotreload.audit.Slf4jHotReloadAuditLogger;
import ru.ludwigandreas.hotreload.binding.ConfigurationBinder;
import ru.ludwigandreas.hotreload.binding.HotReloadTypedConfigFactory;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.hotreload.metrics.HotReloadMetrics;
import ru.ludwigandreas.hotreload.metrics.MetricsSourceChangeListener;
import ru.ludwigandreas.hotreload.metrics.MicrometerHotReloadMetrics;
import ru.ludwigandreas.hotreload.metrics.NoopHotReloadMetrics;
import ru.ludwigandreas.hotreload.propertysource.EnvironmentPropertySourceBridge;
import ru.ludwigandreas.hotreload.template.HotReloadableTemplateLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the autoconfiguration classes actually wire up into a working {@code ApplicationContext} -
 * catching bean-graph mistakes (missing beans, wrong conditions) that unit tests of the individual
 * classes can't.
 */
class HotReloadAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HotReloadAutoConfiguration.class,
                    HotReloadVaultAutoConfiguration.class, HotReloadFreemarkerAutoConfiguration.class));

    @Test
    void coreBeansAreRegisteredWhenEnabled() {
        contextRunner.run((AssertableApplicationContext context) -> {
            assertThat(context).hasSingleBean(SourceReloadCoordinator.class);
            assertThat(context).hasSingleBean(ConfigurationBinder.class);
            assertThat(context).hasSingleBean(HotReloadTypedConfigFactory.class);
            assertThat(context).hasSingleBean(EnvironmentPropertySourceBridge.class);
            assertThat(context).hasBean("hotReloadFileWatcherLifecycle");
            assertThat(context).hasSingleBean(HotReloadAuditLogger.class);
            assertThat(context.getBean(HotReloadAuditLogger.class)).isInstanceOf(Slf4jHotReloadAuditLogger.class);
            assertThat(context).hasSingleBean(AuditingSourceChangeListener.class);
            assertThat(context).hasSingleBean(HotReloadMetrics.class);
            assertThat(context.getBean(HotReloadMetrics.class)).isInstanceOf(NoopHotReloadMetrics.class);
            assertThat(context).hasSingleBean(MetricsSourceChangeListener.class);
            // vault/freemarker autoconfigurations shouldn't activate without their own settings
            assertThat(context).doesNotHaveBean("hotReloadVaultTemplate");
            assertThat(context).doesNotHaveBean(HotReloadableTemplateLoader.class);
        });
    }

    @Test
    void usesMicrometerMetricsWhenAMeterRegistryIsPresent() {
        contextRunner.withBean(SimpleMeterRegistry.class)
                .run(context -> assertThat(context.getBean(HotReloadMetrics.class))
                        .isInstanceOf(MicrometerHotReloadMetrics.class));
    }

    @Test
    void fallsBackToNoopMetricsWhenDisabledEvenWithAMeterRegistryPresent() {
        contextRunner.withBean(SimpleMeterRegistry.class)
                .withPropertyValues("ludwig.hotreload.metrics.enabled=false")
                .run(context -> assertThat(context.getBean(HotReloadMetrics.class))
                        .isInstanceOf(NoopHotReloadMetrics.class));
    }

    @Test
    void customAuditLoggerBeanIsPreferredOverTheDefault() {
        HotReloadAuditLogger custom = entry -> { };
        contextRunner.withBean(HotReloadAuditLogger.class, () -> custom)
                .run(context -> assertThat(context.getBean(HotReloadAuditLogger.class)).isSameAs(custom));
    }

    @Test
    void auditingListenerIsNotRegisteredWhenAuditIsDisabled() {
        contextRunner.withPropertyValues("ludwig.hotreload.audit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AuditingSourceChangeListener.class));
    }

    @Test
    void nothingIsRegisteredWhenDisabled() {
        contextRunner.withPropertyValues("ludwig.hotreload.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SourceReloadCoordinator.class));
    }

    @Test
    void fileWatchLoadsConfiguredFileIntoTheEnvironment(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "demo.greeting=hello", StandardCharsets.UTF_8);

        contextRunner
                .withInitializer(applicationContext ->
                        new HotReloadEnvironmentPostProcessor().postProcessEnvironment(applicationContext.getEnvironment(), null))
                .withPropertyValues(
                        "ludwig.hotreload.files[0].path=" + file,
                        "ludwig.hotreload.file-watch.debounce=50ms")
                .run(context -> {
                    Environment environment = context.getEnvironment();
                    assertThat(environment.getProperty("demo.greeting")).isEqualTo("hello");
                });
    }

    @Test
    void freemarkerSupportOnlyActivatesWhenTemplateDirectoryIsConfigured(@TempDir Path templatesDir) {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(HotReloadableTemplateLoader.class));

        contextRunner
                .withPropertyValues("ludwig.hotreload.freemarker.template-directory=" + templatesDir)
                .run(context -> {
                    assertThat(context).hasSingleBean(HotReloadableTemplateLoader.class);
                    assertThat(context).hasSingleBean(freemarker.template.Configuration.class);
                    assertThat(context).hasBean("hotReloadFreemarkerWatcherLifecycle");
                });
    }

    @Test
    void vaultSupportStaysInactiveWhenDisabled() {
        contextRunner.withPropertyValues("ludwig.hotreload.vault.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("hotReloadVaultTemplate"));
    }
}
