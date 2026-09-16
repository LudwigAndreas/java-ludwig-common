package ru.ludwigandreas.observability.config;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.metrics.ObservabilityMeterFilters;

/**
 * Shapes {@code http.server.requests} into RED metrics and enables annotation-driven instrumentation.
 *
 * <p>Ordered after Boot's metrics autoconfiguration so the registry and its own filters exist first;
 * meter filters are applied in registration order, and these are meant to refine what Boot set up
 * rather than to pre-empt it.
 *
 * <p>Each filter is a separate bean rather than one combined filter, because they are independently
 * switchable and independently overridable: a service that wants the cardinality cap but its own
 * histogram buckets replaces one bean by name and keeps the rest.
 */
@AutoConfiguration(after = {MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class})
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(
        name = {"ludwig.observability.enabled", "ludwig.observability.metrics.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityMetricsAutoConfiguration {

    /** See {@link ObservabilityMeterFilters#commonTags} for why identity is tagged in-process. */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigCommonTagsMeterFilter")
    @ConditionalOnProperty(prefix = "ludwig.observability.metrics", name = "common-tags", matchIfMissing = true)
    public MeterFilter ludwigCommonTagsMeterFilter(ServiceIdentity identity) {
        return ObservabilityMeterFilters.commonTags(identity);
    }

    /**
     * The cardinality cap. Registered first among the HTTP filters and, of everything in this class,
     * the one whose absence can actually take a service down - see
     * {@link ru.ludwigandreas.observability.metrics.UriCardinalityLimitingMeterFilter}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigUriCardinalityMeterFilter")
    @ConditionalOnProperty(prefix = "ludwig.observability.metrics.http", name = "enabled", matchIfMissing = true)
    public MeterFilter ludwigUriCardinalityMeterFilter(ObservabilityProperties properties) {
        return ObservabilityMeterFilters.limitUriCardinality(properties.getMetrics().getHttp().getMaxUriTags());
    }

    @Bean
    @ConditionalOnMissingBean(name = "ludwigIgnoredPathsMeterFilter")
    @ConditionalOnProperty(prefix = "ludwig.observability.metrics.http", name = "enabled", matchIfMissing = true)
    public MeterFilter ludwigIgnoredPathsMeterFilter(ObservabilityProperties properties) {
        return ObservabilityMeterFilters.ignorePaths(properties.getMetrics().getHttp().getIgnoredPaths());
    }

    @Bean
    @ConditionalOnMissingBean(name = "ludwigHttpHistogramMeterFilter")
    @ConditionalOnProperty(prefix = "ludwig.observability.metrics.http", name = "enabled", matchIfMissing = true)
    public MeterFilter ludwigHttpHistogramMeterFilter(ObservabilityProperties properties) {
        ObservabilityProperties.Metrics.Http http = properties.getMetrics().getHttp();
        return ObservabilityMeterFilters.httpServerHistogram(
                http.isPercentilesHistogram(), http.getSlo(), http.getMaximumExpectedValue());
    }

    /**
     * Makes {@code @Timed}, {@code @Counted} and {@code @Observed} work on application beans.
     *
     * <p>Micrometer ships these aspects but registers none of them, so the annotations are silently
     * inert in a plain Spring Boot application - a developer adds {@code @Timed}, sees no metric, and
     * concludes the metric does not work. Registering them is most of the value of this block.
     *
     * <p>Guarded on AspectJ being present and nested accordingly, since Spring AOP is not a
     * dependency of this starter: a service without it still gets every other metric here.
     */
    @Configuration(proxyBeanMethods = false)
    // ProceedingJoinPoint is named as a string rather than as a class literal: AspectJ is not a
    // dependency of this module at all, so a class literal would not compile. TimedAspect ships in
    // micrometer-core and is always present - it is AspectJ underneath it that may not be.
    @ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
    @ConditionalOnProperty(prefix = "ludwig.observability.metrics.aspects", name = "enabled", matchIfMissing = true)
    public static class AspectConfiguration {

        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean
        public TimedAspect ludwigTimedAspect(MeterRegistry registry) {
            return new TimedAspect(registry);
        }

        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean
        public CountedAspect ludwigCountedAspect(MeterRegistry registry) {
            return new CountedAspect(registry);
        }

        /**
         * {@code @Observed} produces a metric and a span from one annotation, which is the form worth
         * reaching for in new code: the two signals then describe the same operation by construction
         * rather than by two annotations that have to be kept in agreement.
         */
        @Bean
        @ConditionalOnBean(ObservationRegistry.class)
        @ConditionalOnMissingBean
        public ObservedAspect ludwigObservedAspect(ObservationRegistry registry) {
            return new ObservedAspect(registry);
        }
    }
}
