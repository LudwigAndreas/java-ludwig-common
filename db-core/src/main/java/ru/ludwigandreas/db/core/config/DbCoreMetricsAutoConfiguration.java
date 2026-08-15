package ru.ludwigandreas.db.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.db.core.metrics.DbCoreMetrics;
import ru.ludwigandreas.db.core.metrics.MicrometerDbCoreMetrics;
import ru.ludwigandreas.db.core.metrics.NoopDbCoreMetrics;

@AutoConfiguration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DbCoreMetricsAutoConfiguration {

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ludwig.db.metrics", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean(DbCoreMetrics.class)
    public DbCoreMetrics micrometerDbCoreMetrics(MeterRegistry registry) {
        return new MicrometerDbCoreMetrics(registry);
    }

    /** Registered whenever the bean above didn't fire (Micrometer absent/disabled/no registry) - keeps callers null-check-free. */
    @Bean
    @ConditionalOnMissingBean(DbCoreMetrics.class)
    public DbCoreMetrics noopDbCoreMetrics() {
        return new NoopDbCoreMetrics();
    }
}
