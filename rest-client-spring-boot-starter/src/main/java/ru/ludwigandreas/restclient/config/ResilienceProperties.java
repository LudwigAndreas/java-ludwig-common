package ru.ludwigandreas.restclient.config;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The five Resilience4j policies and the fallback, for one named client.
 *
 * <p>The decorators are applied in a fixed order - RateLimiter, Bulkhead, CircuitBreaker, Retry,
 * TimeLimiter, then the call - and that order is identical for {@code sync} and {@code async}. It is
 * not configurable, because every other order has a failure mode: a retry outside the breaker
 * retries a call the breaker has already refused, a bulkhead inside the retry lets one logical call
 * hold several permits, and a rate limiter inside the retry spends permits on attempts the limiter
 * was meant to prevent. The README works through each of them.
 */
@Getter
@Setter
public class ResilienceProperties {

    /** Switches all five policies off for this client without deleting their configuration. */
    private Boolean enabled;

    @Valid
    @NestedConfigurationProperty
    private RetryProperties retry = new RetryProperties();

    @Valid
    @NestedConfigurationProperty
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    @Valid
    @NestedConfigurationProperty
    private BulkheadProperties bulkhead = new BulkheadProperties();

    @Valid
    @NestedConfigurationProperty
    private RateLimiterProperties rateLimiter = new RateLimiterProperties();

    @Valid
    @NestedConfigurationProperty
    private TimeLimiterProperties timeLimiter = new TimeLimiterProperties();

    @Valid
    @NestedConfigurationProperty
    private FallbackProperties fallback = new FallbackProperties();
}
