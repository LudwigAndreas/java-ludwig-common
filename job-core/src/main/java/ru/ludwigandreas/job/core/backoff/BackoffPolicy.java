package ru.ludwigandreas.job.core.backoff;

import java.time.Duration;

/**
 * The four numbers that describe an exponential retry schedule, in the form every module in this
 * platform configures it: a first interval, a growth factor, a ceiling, and how much randomness to
 * spread around the result.
 *
 * <p>This is a value type rather than a {@code @ConfigurationProperties} class on purpose. Each
 * module binds its own {@code retry} block under its own prefix - {@code ludwig.outbox.retry},
 * {@code ludwig.reconciliation.tasks.<name>.retry} - and converts it here. Sharing the binding type
 * instead would force every consumer onto one property prefix, which is exactly the coupling
 * {@code job-core} exists to avoid.
 *
 * @param initialInterval delay applied after the first failed attempt; must be positive
 * @param multiplier      factor the interval grows by on each subsequent attempt; {@code 1.0} means a
 *                        fixed interval, which is legal and occasionally what a partner's documented
 *                        retry guidance actually asks for. Must be at least {@code 1.0}
 * @param maxInterval     ceiling the interval is clamped to, before jitter; must be at least
 *                        {@code initialInterval}
 * @param jitter          fraction of the computed interval to randomize by, in {@code [0, 1]}; see
 *                        {@link BackoffCalculator} for why a non-zero value matters
 */
public record BackoffPolicy(Duration initialInterval, double multiplier, Duration maxInterval, double jitter) {

    /** Validates at construction, so a bad retry block fails at wiring time rather than on the first retry. */
    public BackoffPolicy {
        if (initialInterval == null || initialInterval.isNegative() || initialInterval.isZero()) {
            throw new IllegalArgumentException("initialInterval must be positive, was " + initialInterval);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException(
                    "multiplier must be >= 1.0 (a shrinking backoff retries faster the worse it gets), was "
                            + multiplier);
        }
        if (maxInterval == null || maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException(
                    "maxInterval (" + maxInterval + ") must be >= initialInterval (" + initialInterval + ")");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be within [0, 1], was " + jitter);
        }
    }

    /** A policy with no randomization, for callers that deliberately want a deterministic schedule. */
    public static BackoffPolicy of(Duration initialInterval, double multiplier, Duration maxInterval) {
        return new BackoffPolicy(initialInterval, multiplier, maxInterval, 0.0);
    }
}
