package ru.ludwigandreas.outbox.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.outbox.metrics.MicrometerOutboxMetrics;
import ru.ludwigandreas.outbox.metrics.NoopOutboxMetrics;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;

@AutoConfiguration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxMetricsAutoConfiguration {

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ludwig.outbox.metrics", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean(OutboxMetrics.class)
    public OutboxMetrics micrometerOutboxMetrics(MeterRegistry registry) {
        return new MicrometerOutboxMetrics(registry);
    }

    /** Registered whenever the bean above didn't fire (Micrometer absent/disabled/no registry) - keeps callers null-check-free. */
    @Bean
    @ConditionalOnMissingBean(OutboxMetrics.class)
    public OutboxMetrics noopOutboxMetrics() {
        return new NoopOutboxMetrics();
    }
}
