package ru.ludwigandreas.reconciliation.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.reconciliation.metrics.MicrometerReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.NoopReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;

/**
 * Registers instrumentation, and always registers something: the no-op implementation is the fallback
 * so that nothing in the engine has to null-check a metrics call on a hot path.
 *
 * <p>Declared before the main autoconfiguration so its {@code @ConditionalOnMissingBean} fallback is
 * evaluated after this one has had its say.
 */
@AutoConfiguration
@AutoConfigureBefore(ReconciliationAutoConfiguration.class)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationMetricsAutoConfiguration {

    /**
     * Micrometer instrumentation.
     *
     * @param registry the application's meter registry
     * @return the metrics
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ludwig.reconciliation.metrics", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean(ReconciliationMetrics.class)
    public ReconciliationMetrics micrometerReconciliationMetrics(MeterRegistry registry) {
        return new MicrometerReconciliationMetrics(registry);
    }

    /**
     * The fallback, registered whenever the bean above did not fire.
     *
     * @return the no-op metrics
     */
    @Bean
    @ConditionalOnMissingBean(ReconciliationMetrics.class)
    public ReconciliationMetrics noopReconciliationMetrics() {
        return new NoopReconciliationMetrics();
    }
}
