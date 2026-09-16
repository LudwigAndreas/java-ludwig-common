package ru.ludwigandreas.observability.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;
import ru.ludwigandreas.observability.web.CorrelationIdFilter;
import ru.ludwigandreas.observability.web.CorrelationPropagatingRequestInterceptor;
import ru.ludwigandreas.observability.web.ForceSamplingHintFilter;

/**
 * Wires correlation into the servlet request path and onto outgoing HTTP calls.
 *
 * <p>The two filters are registered through {@link FilterRegistrationBean} rather than as bare
 * {@code Filter} beans so that their order and dispatcher types are stated explicitly. Both matter:
 * the ordering relative to Spring Boot's observation filter is load-bearing in opposite directions
 * for the two filters (see each filter's javadoc), and {@code ASYNC} has to be included or a request
 * that is dispatched asynchronously loses its correlation id for the entire second half of its
 * lifecycle - the half that actually produces the response.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Filter.class, OncePerRequestFilter.class})
@ConditionalOnProperty(name = "ludwig.observability.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "ludwigCorrelationIdFilterRegistration")
    @ConditionalOnProperty(
            prefix = "ludwig.observability.correlation", name = "enabled", matchIfMissing = true)
    public FilterRegistrationBean<CorrelationIdFilter> ludwigCorrelationIdFilterRegistration(
            CorrelationContext correlationContext, CorrelationIdResolver resolver,
            TraceContextAccessor traceContext, ObservabilityProperties properties) {
        CorrelationIdFilter filter = new CorrelationIdFilter(
                correlationContext, resolver, traceContext, properties.getCorrelation());
        return register(filter, filter.getOrder());
    }

    /**
     * Registered only when a force header is configured <em>and</em> inbound values are trusted.
     *
     * <p>Both conditions, because the capability is a denial-of-wallet vector: anything that can set
     * the header can make this service trace 100% of its traffic into a backend that is usually
     * billed by span. Defaulting {@code trusted-force-header} to false means the filter simply does
     * not exist unless a deployment has decided its edge strips or authorizes the header - which is
     * a decision, not an oversight.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigForceSamplingHintFilterRegistration")
    @ConditionalOnProperty(
            prefix = "ludwig.observability.tracing.sampling", name = "trusted-force-header", havingValue = "true")
    public FilterRegistrationBean<ForceSamplingHintFilter> ludwigForceSamplingHintFilterRegistration(
            ObservabilityProperties properties) {
        String headerName = properties.getTracing().getSampling().getForceHeader();
        ForceSamplingHintFilter filter = new ForceSamplingHintFilter(headerName);
        return register(filter, filter.getOrder());
    }

    private <T extends Filter> FilterRegistrationBean<T> register(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        // REQUEST and ASYNC, matching ServerHttpObservationFilter. Without ASYNC, a controller
        // returning a DeferredResult or a CompletableFuture would be logged without its correlation
        // id from the dispatch onwards, which is exactly where its interesting work happens.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        return registration;
    }

    /**
     * Adds the correlation header to calls made through {@code RestTemplate}.
     *
     * <p>Nested and guarded so that a servlet application without {@code spring-web}'s client types
     * still loads this configuration. {@code RestTemplateCustomizer} is applied by Boot to every
     * {@code RestTemplate} built from the auto-configured {@code RestTemplateBuilder}, which is how
     * clients should be built and how tracing already reaches them.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    @ConditionalOnProperty(
            prefix = "ludwig.observability.correlation", name = "enabled", matchIfMissing = true)
    public static class RestTemplateConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "ludwigCorrelationRestTemplateCustomizer")
        public RestTemplateCustomizer ludwigCorrelationRestTemplateCustomizer(
                CorrelationContext correlationContext, ObservabilityProperties properties) {
            CorrelationPropagatingRequestInterceptor interceptor = new CorrelationPropagatingRequestInterceptor(
                    correlationContext, properties.getCorrelation().getHeaderName());
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }
    }
}
