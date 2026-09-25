package ru.ludwigandreas.ingest.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.ingest.metrics.IngestMetrics;
import ru.ludwigandreas.ingest.metrics.MicrometerIngestMetrics;

/**
 * Replaces the no-op recorder with the Micrometer one when Micrometer is present.
 *
 * <p>Its own auto-configuration, ordered before {@link FileIngestAutoConfiguration}, for the reason
 * the storage module's S3 half is separate: Micrometer is optional, and a {@code @Bean} method whose
 * signature names {@code MeterRegistry} cannot live in a class Spring always introspects - reading
 * that class's methods on a context without Micrometer throws before any {@code @ConditionalOnClass}
 * on the method is consulted. Only a class-level condition is evaluated from the class file's
 * metadata without loading it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
@AutoConfigureBefore(FileIngestAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ludwig.ingest.metrics", name = "enabled", matchIfMissing = true)
public class FileIngestMetricsAutoConfiguration {

    /**
     * The Micrometer binding.
     *
     * @param registry the meter registry
     * @return the metrics recorder
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(IngestMetrics.class)
    public IngestMetrics micrometerIngestMetrics(MeterRegistry registry) {
        return new MicrometerIngestMetrics(registry);
    }
}
