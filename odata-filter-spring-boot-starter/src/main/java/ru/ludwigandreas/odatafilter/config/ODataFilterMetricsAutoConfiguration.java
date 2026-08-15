package ru.ludwigandreas.odatafilter.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.odatafilter.metrics.MicrometerODataFilterMetrics;
import ru.ludwigandreas.odatafilter.metrics.NoopODataFilterMetrics;
import ru.ludwigandreas.odatafilter.metrics.ODataFilterMetrics;

@AutoConfiguration
@EnableConfigurationProperties(ODataFilterProperties.class)
public class ODataFilterMetricsAutoConfiguration {

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "odata.filter.metrics", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean(ODataFilterMetrics.class)
    public ODataFilterMetrics micrometerODataFilterMetrics(MeterRegistry registry) {
        return new MicrometerODataFilterMetrics(registry);
    }

    /** Registered whenever the bean above didn't fire (Micrometer absent/disabled/no registry) - keeps callers null-check-free. */
    @Bean
    @ConditionalOnMissingBean(ODataFilterMetrics.class)
    public ODataFilterMetrics noopODataFilterMetrics() {
        return new NoopODataFilterMetrics();
    }
}
