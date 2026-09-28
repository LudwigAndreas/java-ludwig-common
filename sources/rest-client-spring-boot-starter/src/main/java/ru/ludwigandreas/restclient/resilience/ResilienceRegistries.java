package ru.ludwigandreas.restclient.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

/**
 * The four Resilience4j registries this module builds its policies in.
 *
 * <p>Registries rather than free-standing instances, and shared rather than one per client, because
 * a registry is what Micrometer binds to: {@code TaggedCircuitBreakerMetrics} and friends observe a
 * registry and publish a meter per entry, tagged with the entry's name. Creating a
 * {@code CircuitBreaker} outside a registry produces an object that works perfectly and is invisible
 * on every dashboard in the estate.
 *
 * <p>Each entry is named after its client, so {@code resilience4j.circuitbreaker.state{name="billing"}}
 * sits next to {@code ludwig.restclient.requests{client="billing"}} and the two can be graphed
 * together.
 *
 * <p>If the application already has these registries as beans - because it uses Resilience4j for
 * something else - those are used instead of new ones, so a service has one set of breakers and one
 * set of metrics rather than two that disagree.
 */
public record ResilienceRegistries(
        CircuitBreakerRegistry circuitBreakers,
        BulkheadRegistry bulkheads,
        ThreadPoolBulkheadRegistry threadPoolBulkheads,
        RateLimiterRegistry rateLimiters,
        TimeLimiterRegistry timeLimiters) {
}
