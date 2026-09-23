package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Retry policy for one named client. */
@Getter
@Setter
public class RetryProperties {

    /** Built-in default: true. Set false to switch retry off without deleting the block. */
    private Boolean enabled;

    /**
     * Total attempts including the first. Built-in default: 3; {@code 1} means "no retry".
     *
     * <p>Attempts multiply load on a dependency that is already failing, so this is also a capacity
     * decision: three attempts against a service at its limit is three times the traffic at the
     * worst possible moment. The circuit breaker outside the retry is what stops that from
     * compounding - see the decorator-order note in the README.
     */
    @Positive
    private Integer maxAttempts;

    /** Base wait before the second attempt. Built-in default: 200ms. */
    private Duration waitDuration;

    /** Each subsequent wait is multiplied by this. Built-in default: 2.0. */
    @DecimalMin("1.0")
    private Double exponentialBackoffMultiplier;

    /**
     * Jitter, as a fraction of the computed wait. Built-in default: 0.5.
     *
     * <p>0.5 means the actual wait is drawn from [0.5 x w, 1.5 x w]. Without jitter every caller
     * that failed at the same instant - which is what a dependency restart produces - retries at the
     * same instant, and the recovering service is knocked over by the synchronized wave before it
     * has finished starting.
     */
    @DecimalMin("0.0")
    private Double randomizedWaitFactor;

    /**
     * Wall-clock budget for the whole call, retries and waits included. Built-in default: 30s.
     *
     * <p>Resilience4j has no such budget: with exponential backoff, {@code max-attempts} alone does
     * not bound how long a caller waits, and a request thread held for two minutes is a thread not
     * serving anyone. When the budget is spent the last failure is rethrown, not swallowed.
     */
    private Duration maxElapsedTime;

    /**
     * HTTP statuses that are retried. Built-in default: 408, 425, 429, 500, 502, 503, 504.
     *
     * <p>Replaced, not merged, when a client declares its own: a client that lists {@code [503]}
     * means only 503, and quietly adding six more to its list would be the starter overruling it.
     */
    private List<Integer> retryOnStatus;

    /**
     * Fully-qualified exception types that are retried, in addition to the built-in transport
     * failures (connect timeouts, socket timeouts, connection resets).
     *
     * <p>A class named here that is not on the classpath is a startup error, not a silent no-op.
     */
    private List<String> retryOnException;

    /**
     * Restrict retries to idempotent methods - GET, HEAD, PUT, DELETE, OPTIONS, TRACE. Built-in
     * default: true.
     *
     * <p>Retrying a POST after a read timeout is how a payment is taken twice: the timeout says
     * nothing about whether the server processed the request, only that the answer did not arrive.
     * A caller that knows better - a POST behind an idempotency key - opts in per request with the
     * {@code X-Ludwig-Retry: true} header, which the pipeline consumes and never sends.
     */
    private Boolean idempotentMethodsOnly;

    /**
     * Honour a {@code Retry-After} header in place of the computed backoff. Built-in default: true.
     *
     * <p>Both forms are understood, seconds and HTTP-date. A server answering 429 has told you when
     * it will be ready; ignoring that in favour of a 200ms backoff is how a rate limit turns into an
     * outage. Capped by {@code max-retry-after} so a hostile or broken peer cannot pin a thread for
     * an hour.
     */
    private Boolean respectRetryAfter;

    /** Ceiling on an honoured {@code Retry-After}. Built-in default: 30s. */
    private Duration maxRetryAfter;
}
