package ru.ludwigandreas.grpc.server.config;

import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.grpc.server.interceptor.CorrelationInterceptor;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;

/**
 * Correlation across the transport, when the platform's observability starter is present. Without
 * it calls are served and carry no correlation id.
 */
@AutoConfiguration(afterName = "ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration")
@ConditionalOnClass(CorrelationContext.class)
@ConditionalOnProperty(prefix = GrpcServerProperties.PREFIX, name = "enabled", matchIfMissing = true)
public class GrpcServerObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnBean({CorrelationContext.class, CorrelationIdResolver.class})
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ludwig.observability.correlation", name = "enabled", matchIfMissing = true)
    public CorrelationInterceptor ludwigGrpcCorrelationInterceptor(
            CorrelationContext correlation, CorrelationIdResolver resolver,
            ObjectProvider<TraceContextAccessor> trace, ObservabilityProperties properties) {
        List<String> inbound = new ArrayList<>();
        inbound.add(properties.getCorrelation().getHeaderName());
        inbound.addAll(properties.getCorrelation().getAdditionalInboundHeaders());
        return new CorrelationInterceptor(correlation, resolver, () -> {
            TraceContextAccessor accessor = trace.getIfAvailable();
            return accessor == null ? null : accessor.currentTraceIdOrNull();
        }, inbound);
    }
}
