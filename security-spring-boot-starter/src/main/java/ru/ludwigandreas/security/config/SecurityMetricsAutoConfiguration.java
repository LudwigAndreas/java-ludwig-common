package ru.ludwigandreas.security.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.security.metrics.MicrometerSecurityMetrics;
import ru.ludwigandreas.security.metrics.NoopSecurityMetrics;
import ru.ludwigandreas.security.metrics.SecurityMetrics;

/**
 * Registers the Micrometer-backed metrics when a registry is present, and a no-op otherwise - so no
 * call site in the module has to check whether metrics are enabled.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.security", name = "enabled", matchIfMissing = true)
public class SecurityMetricsAutoConfiguration {

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(SecurityMetrics.class)
    @ConditionalOnProperty(prefix = "ludwig.security.metrics", name = "enabled", matchIfMissing = true)
    public SecurityMetrics ludwigMicrometerSecurityMetrics(MeterRegistry registry) {
        return new MicrometerSecurityMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityMetrics.class)
    public SecurityMetrics ludwigNoopSecurityMetrics() {
        return new NoopSecurityMetrics();
    }
}
