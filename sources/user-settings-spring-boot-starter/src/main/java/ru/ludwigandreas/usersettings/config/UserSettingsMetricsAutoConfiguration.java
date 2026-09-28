package ru.ludwigandreas.usersettings.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.usersettings.metrics.MicrometerSettingsMetrics;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;

/**
 * Replaces the no-op metrics with the Micrometer implementation.
 *
 * <p>Ordered <em>before</em> the core configuration on purpose: that one registers the no-op under
 * {@code @ConditionalOnMissingBean}, so this bean has to exist by the time it is evaluated. Written
 * the other way round, the no-op would always win and the metrics would silently never appear -
 * which is the kind of failure nobody notices until they go looking for a dashboard.
 */
@AutoConfiguration(before = UserSettingsAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.metrics", name = "enabled", matchIfMissing = true)
public class UserSettingsMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SettingsMetrics.class)
    public SettingsMetrics settingsMetrics(MeterRegistry registry) {
        return new MicrometerSettingsMetrics(registry);
    }
}
