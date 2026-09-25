package ru.ludwigandreas.restclient.config;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.web.CorrelationPropagatingRequestInterceptor;
import ru.ludwigandreas.restclient.core.CallContextSource;
import ru.ludwigandreas.restclient.core.PrincipalSupplier;
import ru.ludwigandreas.restclient.observability.RestClientMeters;
import ru.ludwigandreas.restclient.observability.UriCardinalityLimit;

/**
 * Joins this starter to the platform's existing observability rather than building a second one.
 *
 * <ul>
 *   <li>The correlation id comes from observability-spring-boot-starter's {@code CorrelationContext},
 *       and the blocking clients propagate it with <em>that module's own</em>
 *       {@code CorrelationPropagatingRequestInterceptor}. Nothing about how the id is stored,
 *       resolved or named is re-decided here.</li>
 *   <li>The trace id comes from Micrometer's {@code Tracer}, so an audit record quotes the same id
 *       the span carries.</li>
 *   <li>URI cardinality on {@code ludwig.restclient.requests} is capped by that module's
 *       {@code UriCardinalityLimitingMeterFilter} - the same implementation that protects
 *       {@code http.server.requests}, applied to a different meter prefix.</li>
 * </ul>
 *
 * <p>All of it is conditional: a service without the observability starter gets a working client with
 * no correlation id and no trace id, which is the honest outcome rather than a second, divergent
 * implementation of both.
 */
@AutoConfiguration(after = RestClientAutoConfiguration.class)
@ConditionalOnProperty(prefix = RestClientProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RestClientObservabilityAutoConfiguration {

    /**
     * Caps the {@code uri} tag of this module's request timer.
     *
     * <p>The tag is the URI template, so in normal operation the cardinality is the number of
     * endpoints this service calls and the cap never binds. It binds for an ad-hoc call built from a
     * concatenated string, where the expanded path - chosen by whatever data the service is
     * processing - would otherwise grow the registry without bound.
     */
    @Bean
    @ConditionalOnClass(name = UriCardinalityLimit.MARKER)
    @ConditionalOnProperty(prefix = RestClientProperties.PREFIX + ".metrics", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MeterFilter ludwigRestClientUriCardinalityFilter(RestClientProperties properties) {
        return UriCardinalityLimit.filter(RestClientMeters.REQUESTS,
                properties.getMetrics().getMaxUriTags());
    }

    /**
     * The two beans that name {@code observability-spring-boot-starter}'s types in their signatures.
     *
     * <h2>Why a nested class and not two conditional bean methods</h2>
     *
     * <p>{@code @ConditionalOnClass} on a {@code @Bean} method is read from the bytecode and so does
     * not itself load anything - but Spring still reflects over every method of the configuration
     * class to build its metadata, and reflecting over a method whose parameter type is absent throws
     * {@code NoClassDefFoundError} before any condition is consulted. The failure is
     * "Failed to introspect Class [RestClientObservabilityAutoConfiguration]", and it takes the whole
     * application context with it.
     *
     * <p>Moving those methods into a member class guarded at the class level is Spring Boot's own
     * answer: the nested class is never loaded when the condition does not hold, so its method
     * signatures are never reflected over. The rule to carry away is that a method signature can only
     * be protected by a condition on the type that declares it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CorrelationContext.class)
    public static class PlatformObservabilityIntegration {

        /**
         * The blocking correlation interceptor, reusing observability's own implementation.
         *
         * <p>Registered as a plain {@code ClientHttpRequestInterceptor} bean, which
         * {@code NamedClientFactory} adds to every blocking client it builds.
         *
         * @param correlationContext      the platform's correlation store
         * @param observabilityProperties for the configured header name
         * @return the interceptor
         */
        @Bean
        @ConditionalOnBean(CorrelationContext.class)
        @ConditionalOnMissingBean(name = "ludwigRestClientCorrelationInterceptor")
        public ClientHttpRequestInterceptor ludwigRestClientCorrelationInterceptor(
                CorrelationContext correlationContext, ObservabilityProperties observabilityProperties) {
            return new CorrelationPropagatingRequestInterceptor(correlationContext,
                    observabilityProperties.getCorrelation().getHeaderName());
        }

        /**
         * The ambient identity of the current unit of work, from the platform's own sources.
         *
         * @param correlationContext      the platform's correlation store
         * @param observabilityProperties for the configured header name
         * @param tracer                  Micrometer's tracer, when one is present
         * @param principals              the audited principal, when security is present
         * @return the context source
         */
        @Bean
        @ConditionalOnBean(CorrelationContext.class)
        public CallContextSource ludwigRestClientPlatformCallContextSource(
                CorrelationContext correlationContext, ObservabilityProperties observabilityProperties,
                ObjectProvider<Tracer> tracer, ObjectProvider<PrincipalSupplier> principals) {
            String header = observabilityProperties.getCorrelation().getHeaderName();
            return new PlatformCallContextSource(correlationContext, header, tracer,
                    principals.getIfAvailable());
        }
    }

    /**
     * The audited principal, when the service has Spring Security at all.
     *
     * <p>Conditional on the class rather than on a bean: a service can be a resource server without
     * any bean of a type this module could name, and the {@code SecurityContextHolder} is a static
     * that is either on the classpath or not.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnMissingBean
    public PrincipalSupplier ludwigRestClientPrincipalSupplier() {
        return new SecurityPrincipalSupplier();
    }
}
