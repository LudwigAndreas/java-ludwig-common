package ru.ludwigandreas.job.core.backoff;

import io.github.resilience4j.core.IntervalFunction;

import java.time.Duration;

/**
 * Stateless exponential-backoff calculator for a persisted {@code next_attempt_at} column.
 *
 * <h2>Why resilience4j is used only as an interval function</h2>
 *
 * <p>This deliberately uses resilience4j-core's {@link IntervalFunction} as a pure arithmetic helper
 * rather than {@code Retry.decorateSupplier} or {@code @Retry}. Those retry <em>in-call</em>, holding
 * a thread and a database connection while they sleep. Everything in this platform that retries
 * across process restarts instead writes the next due time to a row and lets the next scheduler tick
 * pick it up, which is what survives a pod being rescheduled mid-backoff.
 *
 * <h2>Why jitter is not optional in practice</h2>
 *
 * <p>Without randomization, every record that failed in the same batch - because the partner was
 * down for that one minute - comes due again at exactly the same instant, and every instance in the
 * cluster wakes to retry them together. The partner, which has just come back, is hit by the entire
 * backlog at once and fails again, and the cluster re-synchronizes on a longer interval. Spreading
 * each interval over a window breaks that lockstep; it is the difference between a recovering
 * partner and a partner this platform keeps knocking over.
 *
 * <p>The randomization applied is the multiplicative form resilience4j calls a randomization factor:
 * the interval is drawn uniformly from {@code [d * (1 - jitter), d * (1 + jitter)]}. A {@code jitter}
 * of {@code 0} yields exactly {@code d} and makes the calculator deterministic, which is what the
 * unit tests of the growth curve rely on.
 */
public class BackoffCalculator {

    private final IntervalFunction intervalFunction;

    /** Creates a calculator for {@code policy}; the policy has already validated its own numbers. */
    public BackoffCalculator(BackoffPolicy policy) {
        this.intervalFunction = policy.jitter() == 0.0
                ? IntervalFunction.ofExponentialBackoff(
                        policy.initialInterval().toMillis(), policy.multiplier(), policy.maxInterval().toMillis())
                : IntervalFunction.ofExponentialRandomBackoff(
                        policy.initialInterval().toMillis(), policy.multiplier(), policy.jitter(),
                        policy.maxInterval().toMillis());
    }

    /**
     * How long to wait before the next attempt.
     *
     * @param attempt 1-based total attempt count <em>after</em> the failure that just occurred, so the
     *                first failure passes {@code 1} and receives {@code initialInterval}
     * @return the delay to add to {@code now()} when writing {@code next_attempt_at}
     */
    public Duration nextDelay(int attempt) {
        return Duration.ofMillis(intervalFunction.apply(attempt));
    }
}
