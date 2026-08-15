package ru.ludwigandreas.common.retryer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Main class for executing operations with retry capabilities.
 */
public class RetryingCall {
    private final RetryPolicy retryPolicy;
    private RetryListener retryListener;
    private MeterRegistry meterRegistry;
    private String operationName;

    private RetryingCall(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy != null ? retryPolicy : new RetryPolicy();
    }

    /**
     * Create a new RetryingCall with the specified retry policy.
     *
     * @param retryPolicy The retry policy to use
     * @return A new RetryingCall instance
     */
    public static RetryingCall with(RetryPolicy retryPolicy) {
        return new RetryingCall(retryPolicy);
    }

    /**
     * Set a listener to be notified of retry events.
     *
     * @param listener The retry listener
     * @return this RetryingCall instance for chaining
     */
    public RetryingCall withListener(RetryListener listener) {
        this.retryListener = listener;
        return this;
    }

    /**
     * Opt into Micrometer instrumentation: an {@code retry.attempts} counter (one increment per
     * attempt made, tagged {@code operation}) and a {@code retry.call} timer (one recording per
     * {@link #call}/{@link #run}/{@link #get} invocation, tagged {@code operation}, {@code outcome}
     * ({@code success}/{@code failure}) and, on failure, {@code exception}). Never called means never
     * instrumented - Micrometer stays entirely optional.
     *
     * @param meterRegistry registry to publish meters to
     * @param operationName identifies this call site in the {@code operation} tag, e.g. {@code "payment-gateway-charge"}
     * @return this RetryingCall instance for chaining
     */
    public RetryingCall withMetrics(MeterRegistry meterRegistry, String operationName) {
        this.meterRegistry = meterRegistry;
        this.operationName = operationName;
        return this;
    }

    /**
     * Execute a Runnable with retry capabilities according to the policy.
     *
     * @param runnable The operation to execute
     * @throws RuntimeException Wrapping any exception that occurred if all retries fail
     */
    public void run(ThrowingRunnable runnable) {
        call(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Execute a Supplier with retry capabilities according to the policy.
     *
     * @param <T>      Return type of the operation
     * @param supplier The operation to execute
     * @return The result of the operation
     * @throws RuntimeException Wrapping any exception that occurred if all retries fail
     */
    public <T> T get(ThrowingSupplier<T> supplier) {
        return call(supplier::get);
    }

    /**
     * Execute a Callable with retry capabilities according to the policy.
     *
     * @param <T>      Return type of the operation
     * @param callable The operation to execute
     * @return The result of the operation
     * @throws RuntimeException Wrapping any exception that occurred if all retries fail
     */
    public <T> T call(Callable<T> callable) {
        int maxRetries = retryPolicy.getMaxRetries();
        Throwable lastException = null;
        Object lastResult = null;
        boolean resultRetryNeeded = false;
        int attemptsMade = 0;
        long startNanos = System.nanoTime();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0 && retryListener != null) {
                if (lastException != null) {
                    retryListener.onRetry(attempt, lastException, null);
                } else if (resultRetryNeeded) {
                    retryListener.onRetry(attempt, null, lastResult);
                }
            }

            attemptsMade++;
            recordAttempt();

            try {
                T result = callable.call();
                lastResult = result;

                // Check if we should retry based on the result
                resultRetryNeeded = retryPolicy.shouldRetryOnResult(result);
                if (!resultRetryNeeded || attempt >= maxRetries) {
                    recordOutcome("success", null, startNanos);
                    return result;
                }

                // Wait before retry
                long delayMs = retryPolicy.getDelayForAttempt(attempt);
                if (delayMs > 0) {
                    sleep(delayMs);
                }
            } catch (Throwable e) {
                lastException = e;
                lastResult = null;
                resultRetryNeeded = false;

                if (attempt >= maxRetries || !retryPolicy.shouldRetryOnException(e)) {
                    break;
                }

                // Wait before next retry
                long delayMs = retryPolicy.getDelayForAttempt(attempt);
                if (delayMs > 0) {
                    sleep(delayMs);
                }
            }
        }

        // If we got here, all attempts failed
        if (lastException != null) {
            recordOutcome("failure", lastException.getClass().getSimpleName(), startNanos);
            if (lastException instanceof RuntimeException) {
                throw (RuntimeException) lastException;
            } else {
                throw new RuntimeException("Operation failed after " + maxRetries + " retries", lastException);
            }
        } else {
            // This would be unusual - all attempts returned results that needed retries
            recordOutcome("failure", RetryExhaustedException.class.getSimpleName(), startNanos);
            throw new RetryExhaustedException("Max retries reached (" + maxRetries + ") with unsatisfactory results");
        }
    }

    private void recordAttempt() {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("retry.attempts")
                .tag("operation", operationName)
                .register(meterRegistry)
                .increment();
    }

    private void recordOutcome(String outcome, String exceptionType, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.Builder timer = Timer.builder("retry.call")
                .tag("operation", operationName)
                .tag("outcome", outcome)
                .tag("exception", exceptionType == null ? "none" : exceptionType);
        timer.register(meterRegistry).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", ie);
        }
    }

    /**
     * Functional interface for operations that may throw exceptions.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Functional interface for suppliers that may throw exceptions.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Exception thrown when all retry attempts are exhausted.
     */
    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message) {
            super(message);
        }

        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Interface for listening to retry events.
     */
    public interface RetryListener {
        /**
         * Called before each retry attempt.
         *
         * @param attempt   Current retry attempt (1-based)
         * @param exception The exception that triggered the retry, or null if retry was due to result
         * @param result    The result that triggered the retry, or null if retry was due to exception
         */
        void onRetry(int attempt, Throwable exception, Object result);
    }
}
