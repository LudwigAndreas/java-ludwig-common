package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Circuit breaker for one named client. */
@Getter
@Setter
public class CircuitBreakerProperties {

    /** Built-in default: true. */
    private Boolean enabled;

    /** Percentage of failed calls in the window that opens the breaker. Built-in default: 50. */
    @DecimalMin("1.0")
    @DecimalMax("100.0")
    private Float failureRateThreshold;

    /**
     * Percentage of slow calls that opens the breaker. Built-in default: 100 (effectively off).
     *
     * <p>Slow-call detection catches the failure mode a failure rate misses entirely: a dependency
     * answering every request correctly, at ten times its normal latency, until every thread in this
     * service is parked on it. Set it together with {@code slow-call-duration-threshold}.
     */
    @DecimalMin("1.0")
    @DecimalMax("100.0")
    private Float slowCallRateThreshold;

    /** Above this, a successful call still counts as slow. Built-in default: the read timeout. */
    private Duration slowCallDurationThreshold;

    /** {@code COUNT_BASED} or {@code TIME_BASED}. Built-in default: COUNT_BASED. */
    private String slidingWindowType;

    /** Calls (COUNT_BASED) or seconds (TIME_BASED) in the window. Built-in default: 100. */
    @Positive
    private Integer slidingWindowSize;

    /**
     * Calls required before the rate is evaluated at all. Built-in default: 10.
     *
     * <p>Without it, the first failed call of the day is a 100% failure rate and the breaker opens
     * on a single blip.
     */
    @Positive
    private Integer minimumNumberOfCalls;

    /** How long the breaker stays open before probing. Built-in default: 30s. */
    private Duration waitDurationInOpenState;

    /** Probe calls admitted in the half-open state. Built-in default: 5. */
    @Positive
    private Integer permittedNumberOfCallsInHalfOpenState;

    /** Leave the open state on a timer rather than on a probe's arrival. Built-in default: true. */
    private Boolean automaticTransitionFromOpenToHalfOpen;

    /**
     * Statuses that count as a failure for the breaker. Built-in default: 500, 502, 503, 504, 429,
     * 408.
     *
     * <p>Note what is absent: 400, 401, 403, 404, 409, 422. A client error is this service sending
     * something the peer rejected - the peer is healthy, and opening the breaker on it takes down a
     * working dependency because of a bug in the caller. That is the single most common way a
     * circuit breaker makes an incident worse, which is why the default list is stated here rather
     * than inherited from "any non-2xx".
     */
    private List<Integer> recordFailureOnStatus;

    /**
     * Fully-qualified exception types that count as a failure, in addition to transport failures.
     *
     * <p>Anything not listed and not a transport failure is recorded as a success: a deserialization
     * error is this service's problem, not the peer's.
     */
    private List<String> recordFailureOnException;
}
