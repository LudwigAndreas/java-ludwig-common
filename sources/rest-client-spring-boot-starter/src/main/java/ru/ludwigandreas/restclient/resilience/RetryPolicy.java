package ru.ludwigandreas.restclient.resilience;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.ludwigandreas.restclient.config.RetryProperties;

/**
 * Whether to retry, and how long to wait first.
 *
 * <h2>Why this is not Resilience4j's {@code Retry} decorator</h2>
 *
 * <p>Resilience4j supplies the hard part - {@link IntervalFunction#ofExponentialRandomBackoff},
 * which is what produces the jittered backoff below, and which this class uses rather than
 * reimplements. What its {@code Retry} decorator cannot express is the other three things this
 * policy needs:
 *
 * <ul>
 *   <li><strong>{@code Retry-After}.</strong> The wait has to come from the response, and the
 *       decorator's interval is a function of the attempt number only.</li>
 *   <li><strong>An elapsed-time budget.</strong> {@code max-attempts} with exponential backoff does
 *       not bound how long a caller waits; three attempts at a 2x multiplier from 2s is already
 *       fourteen seconds of waiting on top of three timeouts.</li>
 *   <li><strong>The same behaviour in both modes.</strong> The decorator's blocking loop has no
 *       reactive equivalent that shares its decisions, so a {@code sync} and an {@code async} client
 *       configured identically would drift apart - which is exactly the surprise this starter exists
 *       to remove.</li>
 * </ul>
 *
 * <p>The consequence, stated plainly because it is a real trade: {@code resilience4j.retry.*} meters
 * are not published for these clients. {@code ludwig.restclient.retries} is, tagged by client and by
 * reason, and it is published identically for both modes. The other four policies <em>are</em>
 * Resilience4j's own objects from the shared, Micrometer-bound registries.
 */
public class RetryPolicy {

    private final boolean enabled;
    private final int maxAttempts;
    private final Set<Integer> retryOnStatus;
    private final List<Class<? extends Throwable>> retryOnException;
    private final boolean idempotentOnly;
    private final boolean respectRetryAfter;
    private final Duration maxRetryAfter;
    private final Duration maxElapsed;
    private final IntervalFunction backoff;
    private final Clock clock;

    /**
     * Creates the policy for one client from its merged {@code retry} block.
     *
     * @param exceptionTypes the classes named in {@code retry-on-exception}, already resolved. They
     *                       are resolved at startup rather than per call so that a class name that
     *                       does not exist fails the context instead of silently never matching
     */
    public RetryPolicy(RetryProperties properties, List<Class<? extends Throwable>> exceptionTypes,
                       Clock clock) {
        this.enabled = Boolean.TRUE.equals(properties.getEnabled()) && properties.getMaxAttempts() > 1;
        this.maxAttempts = properties.getMaxAttempts();
        this.retryOnStatus = new HashSet<>(properties.getRetryOnStatus());
        this.retryOnException = List.copyOf(exceptionTypes);
        this.idempotentOnly = Boolean.TRUE.equals(properties.getIdempotentMethodsOnly());
        this.respectRetryAfter = Boolean.TRUE.equals(properties.getRespectRetryAfter());
        this.maxRetryAfter = properties.getMaxRetryAfter();
        this.maxElapsed = properties.getMaxElapsedTime();
        this.backoff = IntervalFunction.ofExponentialRandomBackoff(
                properties.getWaitDuration(),
                properties.getExponentialBackoffMultiplier(),
                properties.getRandomizedWaitFactor());
        this.clock = clock;
    }

    /** Attempts allowed for one logical call, the first included. */
    public int maxAttempts() {
        return enabled ? maxAttempts : 1;
    }

    /**
     * Whether {@code outcome} should be retried.
     *
     * @param method       the HTTP method, for the idempotence rule
     * @param retryOptIn   {@code TRUE} or {@code FALSE} when the caller set {@code X-Ludwig-Retry},
     *                     {@code null} when it did not
     * @param elapsedMillis how long the logical call has already taken, waits included
     */
    public boolean shouldRetry(AttemptOutcome outcome, String method, Boolean retryOptIn,
                               long elapsedMillis) {
        if (!enabled || outcome.attempt() >= maxAttempts) {
            return false;
        }
        if (!methodAllowsRetry(method, retryOptIn, outcome)) {
            return false;
        }
        if (elapsedMillis + minimumNextWait(outcome) > maxElapsed.toMillis()) {
            // Checked before the wait, not after: spending the budget asleep and then discovering it
            // is gone is the same as not having a budget.
            return false;
        }
        return outcome.responded()
                ? retryOnStatus.contains(outcome.statusCode())
                : matchesException(outcome.failure());
    }

    /**
     * How long to wait before the next attempt.
     *
     * <p>{@code Retry-After} wins over the computed backoff whenever the peer sent one, because the
     * peer knows something the backoff curve does not.
     */
    public long waitMillis(AttemptOutcome outcome) {
        if (respectRetryAfter && outcome.retryAfterMillis() >= 0) {
            return Math.min(outcome.retryAfterMillis(), maxRetryAfter.toMillis());
        }
        return backoff.apply(outcome.attempt());
    }

    /** Why the retry happened, as a metric tag and a listener argument. */
    public String reason(AttemptOutcome outcome) {
        if (outcome.responded()) {
            return "status:" + outcome.statusCode();
        }
        return outcome.failure() == null ? "unknown" : outcome.failure().getClass().getSimpleName();
    }

    private boolean methodAllowsRetry(String method, Boolean retryOptIn, AttemptOutcome outcome) {
        if (retryOptIn != null) {
            // An explicit header is the caller's decision in both directions: it can enable a retry
            // on a POST it has made safe, and it can disable one on a GET whose result it does not
            // want re-fetched.
            return retryOptIn;
        }
        if (!idempotentOnly || IdempotentMethods.contains(method)) {
            return true;
        }
        // The one exception the policy makes on its own: a connection that never opened carried
        // nothing, so repeating it cannot repeat an effect.
        return isConnectionFailure(outcome.failure());
    }

    private boolean isConnectionFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.NoRouteToHostException) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    private boolean matchesException(Throwable failure) {
        if (failure == null) {
            return false;
        }
        if (TransportFailures.isRetryable(failure)) {
            return true;
        }
        for (Class<? extends Throwable> type : retryOnException) {
            if (type.isInstance(failure) || type.isInstance(failure.getCause())) {
                return true;
            }
        }
        return false;
    }

    /** A lower bound on the next wait, used to decide whether the budget can afford another attempt. */
    private long minimumNextWait(AttemptOutcome outcome) {
        return respectRetryAfter && outcome.retryAfterMillis() >= 0
                ? Math.min(outcome.retryAfterMillis(), maxRetryAfter.toMillis())
                : 0;
    }

    /** The clock the policy measures elapsed time against; exposed so both pipelines share one. */
    public Clock clock() {
        return clock;
    }
}
