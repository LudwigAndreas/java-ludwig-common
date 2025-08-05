package ru.ludwigandreas.common.retryer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Configurable policy for retry behavior.
 */
public class RetryPolicy {
    private boolean enabled = true;
    private int maxRetries = 0;
    private long delay = 0;
    private TimeUnit delayTimeUnit = TimeUnit.MILLISECONDS;
    private boolean increasingDelay = false;
    private double delayFactor = 2.0;
    private long maxDelay = Long.MAX_VALUE;
    private final Set<Class<? extends Throwable>> retryableExceptions = new HashSet<>();
    private Predicate<Throwable> exceptionPredicate;
    private Predicate<Object> resultPredicate;

    /**
     * Creates a new RetryPolicy with default settings.
     */
    public RetryPolicy() {
        // Default constructor with default settings
    }

    /**
     * Specify exception types that should trigger a retry.
     *
     * @param exceptions Exception classes to retry on
     * @return this RetryPolicy instance for chaining
     */
    @SafeVarargs
    public final RetryPolicy retryOn(Class<? extends Throwable>... exceptions) {
        this.retryableExceptions.addAll(Arrays.asList(exceptions));
        return this;
    }

    /**
     * Specify a custom predicate to determine if an exception should trigger a retry.
     *
     * @param predicate Predicate that returns true if the exception should trigger a retry
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy retryIf(Predicate<Throwable> predicate) {
        this.exceptionPredicate = predicate;
        return this;
    }

    /**
     * Specify a predicate to determine if a result should trigger a retry.
     * This allows retrying when a method returns successfully but the result
     * doesn't meet certain criteria.
     *
     * @param predicate Predicate that returns true if the result should trigger a retry
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy retryOnResult(Predicate<Object> predicate) {
        this.resultPredicate = predicate;
        return this;
    }

    /**
     * Set the maximum number of retry attempts.
     *
     * @param maxRetries Maximum number of retries
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy withMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * Set a fixed delay between retry attempts.
     *
     * @param delay    Time to wait between retries
     * @param timeUnit Time unit for the delay
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy withDelay(long delay, TimeUnit timeUnit) {
        if (delay < 0) {
            throw new IllegalArgumentException("Delay cannot be negative");
        }
        this.delay = delay;
        this.delayTimeUnit = timeUnit;
        return this;
    }

    /**
     * Configure whether the delay should increase with each retry attempt.
     * When enabled, each retry will wait longer than the previous one.
     *
     * @param increasingDelay Whether to use increasing delay
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy withIncreasingDelay(boolean increasingDelay) {
        this.increasingDelay = increasingDelay;
        return this;
    }

    /**
     * Set the factor by which the delay increases with each retry when using increasing delay.
     * For example, a factor of 2.0 will double the delay with each retry.
     *
     * @param factor Multiplier for delay increase (default is 2.0)
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy withDelayFactor(double factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("Delay factor must be positive");
        }
        this.delayFactor = factor;
        return this;
    }

    /**
     * Set the maximum delay between retries when using increasing delay.
     *
     * @param maxDelay Maximum delay value
     * @param timeUnit Time unit for the maximum delay
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy withMaxDelay(long maxDelay, TimeUnit timeUnit) {
        if (maxDelay < 0) {
            throw new IllegalArgumentException("Max delay cannot be negative");
        }
        this.maxDelay = timeUnit.toMillis(maxDelay);
        return this;
    }

    /**
     * Enable or disable retry functionality.
     * When disabled, calls will execute once with no retries.
     *
     * @param enabled Whether retry functionality is enabled
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Check if an exception should trigger a retry according to this policy.
     *
     * @param throwable The exception to check
     * @return true if the exception should trigger a retry
     */
    boolean shouldRetryOnException(Throwable throwable) {
        if (!enabled) {
            return false;
        }

        // Check if the exception is in our retryable exceptions set
        if (!retryableExceptions.isEmpty()) {
            for (Class<? extends Throwable> retryableException : retryableExceptions) {
                if (retryableException.isInstance(throwable)) {
                    return true;
                }
            }
        }

        // Check the custom predicate if specified
        return exceptionPredicate != null && exceptionPredicate.test(throwable);
    }

    /**
     * Check if a result should trigger a retry according to this policy.
     *
     * @param result The result to check
     * @return true if the result should trigger a retry
     */
    boolean shouldRetryOnResult(Object result) {
        return enabled && resultPredicate != null && resultPredicate.test(result);
    }

    /**
     * Calculate the delay for a specific retry attempt.
     *
     * @param attempt Current retry attempt (0-based)
     * @return Delay in milliseconds for the given attempt
     */
    long getDelayForAttempt(int attempt) {
        if (delay == 0 || attempt == 0) {
            return 0;
        }

        long currentDelay = delayTimeUnit.toMillis(delay);

        if (increasingDelay && attempt > 0) {
            // Calculate exponential backoff
            double factor = Math.pow(delayFactor, attempt);
            long calculatedDelay = (long) (currentDelay * factor);

            // Ensure we don't exceed max delay or have overflow
            return Math.min(calculatedDelay, maxDelay);
        }

        return currentDelay;
    }

    /**
     * @return Maximum number of retry attempts
     */
    int getMaxRetries() {
        return enabled ? maxRetries : 0;
    }
}
