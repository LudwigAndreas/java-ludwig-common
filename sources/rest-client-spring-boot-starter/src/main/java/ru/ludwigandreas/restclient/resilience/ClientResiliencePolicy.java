package ru.ludwigandreas.restclient.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.Getter;

/**
 * One named client's resilience policy: the decorators, in the order they wrap the call.
 *
 * <h2>The order, and why it is not configurable</h2>
 *
 * <p>{@code RateLimiter -> Bulkhead -> CircuitBreaker -> Retry -> TimeLimiter -> call}. Identical for
 * {@code sync} and {@code async}. Every other order has a concrete failure:
 *
 * <ul>
 *   <li><strong>Retry inside the breaker, not outside.</strong> Outside, the breaker sees one
 *       failure per logical call instead of one per attempt, so a dependency failing every request
 *       takes three times as long to trip it - and each of those calls is still making three
 *       attempts against a service that is already down. Inside, the breaker counts what actually
 *       happened, and once it opens, the retries stop happening at all. That second effect is the
 *       important one: retry amplifies load precisely when the dependency can least afford it, and
 *       the breaker is the only thing that stops the amplification.</li>
 *   <li><strong>Bulkhead outside the retry.</strong> Inside, one logical call acquires and releases
 *       a permit per attempt, so the concurrency cap stops bounding the number of calls in flight -
 *       which is the only thing it was for.</li>
 *   <li><strong>RateLimiter outermost.</strong> Anywhere else it spends permits on attempts and on
 *       calls the breaker was going to refuse anyway, so the rate seen by the partner is some
 *       multiple of the configured one.</li>
 *   <li><strong>TimeLimiter innermost.</strong> It bounds one attempt. Wrapped around the retry it
 *       would bound the whole call, which is what {@code retry.max-elapsed-time} already does, and
 *       the first attempt's cancellation would look like the call's deadline.</li>
 * </ul>
 *
 * <p>Any of the five may be absent; a disabled policy is a {@code null} field rather than a
 * pass-through object, so the pipeline's cost for a client that only uses retry is one null check
 * per policy rather than four virtual calls.
 */
@Getter
public class ClientResiliencePolicy {

    private final String clientName;

    /** Outermost. {@code null} when disabled. */
    private final RateLimiter rateLimiter;

    /** Semaphore bulkhead, or {@code null} when disabled or thread-pool-based. */
    private final Bulkhead bulkhead;

    /** Thread-pool bulkhead, or {@code null} when disabled or semaphore-based. */
    private final ThreadPoolBulkhead threadPoolBulkhead;

    /** {@code null} when disabled. */
    private final CircuitBreaker circuitBreaker;

    /**
     * Never {@code null}; a disabled retry is a policy whose {@code maxAttempts()} is 1.
     *
     * <p>Volatile and replaceable, unlike the four decorators above. Retry is pure decision-making
     * derived from properties, so a new policy can take over between one call and the next; a
     * breaker's sliding window and a bulkhead's permits are live state that a replacement would
     * silently reset - which is why {@code resilience.retry.*} is refreshable and the rest is not.
     */
    private volatile RetryPolicy retry;

    /** {@code async} only; {@code null} otherwise. */
    private final TimeLimiter timeLimiter;

    /** Statuses the breaker records as failures. */
    private final Set<Integer> failureStatuses;

    /** Exception types the breaker records as failures, beyond the transport ones. */
    private final List<Class<? extends Throwable>> failureExceptions;

    /** Above this, a successful call is also counted as a slow one. */
    private final Duration slowCallThreshold;

    /** Creates the policy; a {@code null} decorator means that policy is disabled. */
    // CHECKSTYLE.OFF: ParameterNumber - nine collaborators, one per policy plus its predicates.
    // Splitting them into a builder would hide, rather than reduce, the fact that a resilience
    // policy genuinely has this many parts.
    public ClientResiliencePolicy(String clientName, RateLimiter rateLimiter, Bulkhead bulkhead,
                                  ThreadPoolBulkhead threadPoolBulkhead, CircuitBreaker circuitBreaker,
                                  RetryPolicy retry, TimeLimiter timeLimiter,
                                  Set<Integer> failureStatuses,
                                  List<Class<? extends Throwable>> failureExceptions,
                                  Duration slowCallThreshold) {
        this.clientName = clientName;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.threadPoolBulkhead = threadPoolBulkhead;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.failureStatuses = Set.copyOf(failureStatuses);
        this.failureExceptions = List.copyOf(failureExceptions);
        this.slowCallThreshold = slowCallThreshold;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /**
     * Swaps in a retry policy rebuilt from changed properties.
     *
     * <p>A call already in its retry loop keeps the policy it started with, which is correct: a
     * logical call should not change the rules halfway through deciding whether to repeat itself.
     */
    public void updateRetry(RetryPolicy replacement) {
        this.retry = replacement;
    }

    /**
     * Whether {@code outcome} counts as a failure for the circuit breaker.
     *
     * <p>Note what is <em>not</em> a failure: any 4xx that is not in the configured list. A client
     * error means this service sent something the peer rejected, so the peer is healthy, and opening
     * the breaker on it takes a working dependency out of service because of a bug in the caller.
     * That is the single most common way a circuit breaker makes an incident worse.
     */
    public boolean isFailure(AttemptOutcome outcome) {
        if (outcome.responded()) {
            return failureStatuses.contains(outcome.statusCode());
        }
        Throwable failure = outcome.failure();
        if (TransportFailures.isTransportFailure(failure)) {
            return true;
        }
        for (Class<? extends Throwable> type : failureExceptions) {
            if (type.isInstance(failure)) {
                return true;
            }
        }
        // Anything else - a deserialization error, a bug in an interceptor - is this service's own
        // problem and must not be charged to the dependency's health.
        return false;
    }
}
