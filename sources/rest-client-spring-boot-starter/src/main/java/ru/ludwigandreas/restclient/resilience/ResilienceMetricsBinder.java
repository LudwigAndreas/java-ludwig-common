package ru.ludwigandreas.restclient.resilience;

import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedThreadPoolBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;

/**
 * Publishes the Resilience4j registries' own meters.
 *
 * <p>Without this, the registries work perfectly and are invisible: {@code
 * resilience4j.circuitbreaker.state}, {@code resilience4j.bulkhead.available.concurrent.calls} and
 * the rest are produced by Micrometer binders that have to be attached to a registry explicitly, and
 * a breaker that opens with no meter behind it is a state change nobody can alert on.
 *
 * <p>Each entry is tagged with its own name, which is the client's name, so these sit next to this
 * module's {@code ludwig.restclient.*} meters and a dashboard can put the breaker's state on the same
 * row as the dependency's error rate.
 *
 * <p>Binding is idempotent by construction: Micrometer returns the existing meter for an identical
 * name and tag set, so a service that also uses {@code resilience4j-spring-boot3} - and therefore
 * already bound the same registries - ends up with one set of meters rather than two.
 */
public class ResilienceMetricsBinder implements InitializingBean {

    private final ResilienceRegistries registries;
    private final MeterRegistry meterRegistry;

    /** Binds {@code registries} to {@code meterRegistry} when the context finishes starting. */
    public ResilienceMetricsBinder(ResilienceRegistries registries, MeterRegistry meterRegistry) {
        this.registries = registries;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registries.circuitBreakers())
                .bindTo(meterRegistry);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registries.bulkheads()).bindTo(meterRegistry);
        TaggedThreadPoolBulkheadMetrics.ofThreadPoolBulkheadRegistry(registries.threadPoolBulkheads())
                .bindTo(meterRegistry);
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(registries.rateLimiters()).bindTo(meterRegistry);
        TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(registries.timeLimiters()).bindTo(meterRegistry);
    }
}
