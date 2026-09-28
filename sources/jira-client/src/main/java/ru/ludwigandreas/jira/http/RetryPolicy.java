package ru.ludwigandreas.jira.http;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When to re-send a failed request, and how long to wait first.
 *
 * <p>The defaults are chosen for a Jira Server behind a load balancer, which is where this client is
 * expected to run:
 *
 * <ul>
 *   <li><b>Only idempotent requests, by default.</b> A {@code POST} that timed out may already have created
 *       an issue; re-sending it creates a second one. Jira has no idempotency-key mechanism to make that
 *       safe, so the only correct default is not to retry. Endpoints that are semantically reads expressed
 *       as {@code POST} - {@code /search}, Structure's {@code /value} - opt in individually through
 *       {@link JiraRequest.Builder#retryable(boolean)}.</li>
 *   <li><b>429, 502, 503 and 504 only</b>, plus transport failures. A 500 is deliberately excluded: Jira
 *       returns 500 for a genuine server-side bug on a request that will fail identically every time, and
 *       retrying it three times only triples the load on an instance that is already struggling.</li>
 *   <li><b>Full jitter.</b> The delay is drawn uniformly from {@code [0, backoff]} rather than being
 *       {@code backoff} exactly. When a Jira node restarts, every client that was mid-request retries at
 *       once; an unjittered backoff reconverges them into a second thundering herd at exactly the same
 *       instant, and a third one after that.</li>
 * </ul>
 *
 * <p>A server-supplied {@code Retry-After} always wins over the computed backoff, capped by
 * {@link #maxBackoff()} so that a proxy asking for an hour cannot park a request thread for an hour.
 */
public final class RetryPolicy {

    private static final Set<Integer> DEFAULT_RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(20);
    private static final double DEFAULT_MULTIPLIER = 2.0;

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double multiplier;
    private final Set<Integer> retryableStatuses;
    private final boolean jitter;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.multiplier = builder.multiplier;
        this.retryableStatuses = Set.copyOf(builder.retryableStatuses);
        this.jitter = builder.jitter;
    }

    /** The policy described in this class's documentation: three attempts, 500 ms base, full jitter. */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    /** A policy that never retries, for callers that own their own retry loop. */
    public static RetryPolicy none() {
        return builder().maxAttempts(1).build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Total attempts allowed, including the first one. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Ceiling on any single wait, including one derived from {@code Retry-After}. */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /** Statuses considered transient. */
    public Set<Integer> retryableStatuses() {
        return retryableStatuses;
    }

    /**
     * Whether another attempt is allowed after a response with this status.
     *
     * @param request the request that was sent
     * @param status the status that came back
     * @param attempt 1-based number of the attempt that just finished
     * @return true when the request should be sent again
     */
    public boolean shouldRetryStatus(JiraRequest request, int status, int attempt) {
        return attempt < maxAttempts && request.isRetryable() && retryableStatuses.contains(status);
    }

    /**
     * Whether another attempt is allowed after a transport failure.
     *
     * @param request the request that was sent
     * @param attempt 1-based number of the attempt that just finished
     * @return true when the request should be sent again
     */
    public boolean shouldRetryFailure(JiraRequest request, int attempt) {
        return attempt < maxAttempts && request.isRetryable();
    }

    /**
     * How long to wait before attempt {@code attempt + 1}.
     *
     * @param attempt 1-based number of the attempt that just finished
     * @param retryAfter the server's {@code Retry-After}, when it sent one
     * @return the delay to sleep, never negative and never above {@link #maxBackoff()}
     */
    public Duration backoff(int attempt, Optional<Duration> retryAfter) {
        if (retryAfter.isPresent()) {
            Duration asked = retryAfter.get();
            return asked.compareTo(maxBackoff) > 0 ? maxBackoff : asked;
        }
        double exponential = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1.0);
        long capped = (long) Math.min(exponential, (double) maxBackoff.toMillis());
        long millis = jitter && capped > 0 ? ThreadLocalRandom.current().nextLong(capped + 1) : capped;
        return Duration.ofMillis(millis);
    }

    /** Fluent builder for {@link RetryPolicy}. */
    public static final class Builder {

        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private double multiplier = DEFAULT_MULTIPLIER;
        private Set<Integer> retryableStatuses = DEFAULT_RETRYABLE_STATUSES;
        private boolean jitter = true;

        private Builder() {
        }

        /** Total attempts including the first; 1 disables retrying. */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** Backoff before the second attempt, doubled (by default) for each further one. */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        /** Ceiling on any single wait. */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /** Growth factor between successive backoffs. */
        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        /** Replaces the set of statuses treated as transient. */
        public Builder retryableStatuses(Set<Integer> retryableStatuses) {
            this.retryableStatuses = retryableStatuses;
            return this;
        }

        /** Turns full jitter off, which is only ever right in a test that asserts on exact delays. */
        public Builder jitter(boolean jitter) {
            this.jitter = jitter;
            return this;
        }

        /** Builds the immutable policy. */
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
