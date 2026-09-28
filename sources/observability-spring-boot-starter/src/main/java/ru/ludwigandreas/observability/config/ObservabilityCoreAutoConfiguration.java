package ru.ludwigandreas.observability.config;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;

/**
 * The beans every other part of this module builds on: who the service is, what the correlation id
 * is, and how to read the current trace.
 *
 * <p>Separated from the signal-specific autoconfigurations because these four have no optional
 * dependencies at all - they work in a batch worker with no servlet container, no broker and no
 * exporter - and because each of the others needs some of them. Keeping them in one always-on
 * configuration is what lets the rest be guarded on classpath conditions without any of them having
 * to re-declare shared beans and risk defining them twice.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ludwig.observability.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityCoreAutoConfiguration {

    /**
     * The service identity, bound straight from properties.
     *
     * <p>No derivation happens here - it has already happened, in
     * {@link ObservabilityEnvironmentPostProcessor}, which wrote the resolved values into the
     * environment as defaults. That ordering is what guarantees the OTLP resource attributes and
     * these metric tags describe the same service: they come from the same resolution, not from two.
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceIdentity ludwigServiceIdentity(ObservabilityProperties properties) {
        ObservabilityProperties.Service service = properties.getService();
        return new ServiceIdentity(service.getName(), service.getNamespace(), service.getVersion(),
                service.getEnvironment(), service.getInstance());
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationContext ludwigCorrelationContext(ObservabilityProperties properties) {
        return new CorrelationContext(properties.getCorrelation().getMdcKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdResolver ludwigCorrelationIdResolver(ObservabilityProperties properties) {
        ObservabilityProperties.Correlation correlation = properties.getCorrelation();
        return new CorrelationIdResolver(correlation.getAllowedPattern(), correlation.getMaxLength(),
                correlation.isGenerateIfAbsent());
    }

    /**
     * Reads the current trace, tolerating the absence of a tracer.
     *
     * <p>{@code ObjectProvider} rather than a direct injection: tracing can be switched off with
     * {@code management.tracing.enabled=false}, and correlation, JSON logging and metrics must all
     * keep working when it is. Handing the accessor a null tracer is the supported case - see
     * {@link TraceContextAccessor}.
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceContextAccessor ludwigTraceContextAccessor(ObjectProvider<Tracer> tracer) {
        return new TraceContextAccessor(tracer.getIfAvailable());
    }
}
