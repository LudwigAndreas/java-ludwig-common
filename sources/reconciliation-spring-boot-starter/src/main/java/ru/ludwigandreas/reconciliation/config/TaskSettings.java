package ru.ludwigandreas.reconciliation.config;

import ru.ludwigandreas.job.core.backoff.BackoffPolicy;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;

import java.time.Duration;
import java.util.Optional;

/**
 * One task's settings after {@code defaults} have been merged in and every gap filled from the
 * module's built-in values.
 *
 * <p>Every field is non-null. That is the point of the type: the engine reads settings on every tick
 * of every task, and a three-way "task, then defaults, then built-in" lookup repeated at each of
 * those points is both slow and the kind of thing that gets one branch wrong once and produces a
 * setting that works everywhere except in the one code path that forgot the middle step.
 *
 * @param name           the task name, matching its configuration key and its bean's annotation
 * @param enabled        whether the task runs
 * @param mode           whether fetched records are staged before being applied
 * @param notFound       what to do when the partner does not know a key
 * @param fetch          shape-specific fetch settings
 * @param hotSchedule    when the hot pass runs
 * @param coldSchedule   when the cold full sweep runs, if this task is tiered
 * @param runTimeout     how long one run may take before its lock is presumed abandoned
 * @param demand         how much demand one run may take on
 * @param apply          when and how staged records are applied
 * @param retry          the retry budget and its backoff curve
 * @param audit          what is audited, and where
 * @param restClient     the named REST client the fetcher uses, if it names one
 * @param quota          the partner-scoped concurrency budget, if this task is gated by one
 * @param rateLimit      the partner-scoped rate budget, if this task is gated by one
 * @param job            the asynchronous-job settings, present exactly when the shape is async-job
 */
public record TaskSettings(String name,
                           boolean enabled,
                           ProcessingMode mode,
                           NotFoundPolicy notFound,
                           FetchSettings fetch,
                           ScheduleSpec hotSchedule,
                           ScheduleSpec coldSchedule,
                           Duration runTimeout,
                           DemandSettings demand,
                           ApplySettings apply,
                           RetrySettings retry,
                           AuditSettings audit,
                           String restClient,
                           String quota,
                           String rateLimit,
                           JobSettings job) {

    /** Whether this task splits its demand into a fast hot pass and a slow cold sweep. */
    public boolean isTiered() {
        return coldSchedule != null;
    }

    /** The cold sweep's schedule, if this task is tiered. */
    public Optional<ScheduleSpec> coldScheduleOrEmpty() {
        return Optional.ofNullable(coldSchedule);
    }

    /** The named REST client this task's fetcher uses, if it names one. */
    public Optional<String> restClientName() {
        return Optional.ofNullable(restClient);
    }

    /** The quota gating this task's calls, if any. */
    public Optional<String> quotaName() {
        return Optional.ofNullable(quota);
    }

    /** The rate limit gating this task's calls, if any. */
    public Optional<String> rateLimitName() {
        return Optional.ofNullable(rateLimit);
    }

    /** The asynchronous-job settings, present exactly when the shape is async-job. */
    public Optional<JobSettings> jobOrEmpty() {
        return Optional.ofNullable(job);
    }

    /**
     * Shape-specific fetch settings.
     *
     * @param shape           which of the four shapes
     * @param batchSize       keys per call for {@code batched}
     * @param pageSize        records per page for {@code paged} and for job-result collection
     * @param maxConcurrency  calls this task may have in flight at once
     * @param checkpoint      whether a paged sweep persists its cursor and resumes
     * @param submitBatchSize keys per submitted job for {@code async-job}
     */
    public record FetchSettings(FetchShape shape,
                                int batchSize,
                                int pageSize,
                                int maxConcurrency,
                                boolean checkpoint,
                                int submitBatchSize) {
    }

    /**
     * How much demand one run may take on.
     *
     * @param maxRecordsPerRun hard cap on records per run
     * @param freshnessTarget  how stale this task's data is allowed to get; the denominator of the
     *                         freshness-lag gauge
     * @param incremental      whether the task receives a persisted watermark as {@code updated-since}
     */
    public record DemandSettings(int maxRecordsPerRun, Duration freshnessTarget, boolean incremental) {
    }

    /**
     * When and how staged records are applied.
     *
     * @param schedule     when the apply pass runs
     * @param batchSize    records claimed per pass
     * @param drainTimeout how long shutdown waits for a pass that is already running
     */
    public record ApplySettings(ScheduleSpec schedule, int batchSize, Duration drainTimeout) {
    }

    /**
     * The retry budget and its backoff curve.
     *
     * @param maxAttempts attempts before a record is quarantined
     * @param backoff     the curve, in the form {@code job-core} consumes
     */
    public record RetrySettings(int maxAttempts, BackoffPolicy backoff) {

        /**
         * The worst case this budget can take, used to check it against {@code run-timeout}.
         *
         * @return the sum of every interval the budget can produce, ignoring jitter
         */
        public Duration worstCaseDuration() {
            Duration total = Duration.ZERO;
            Duration interval = backoff.initialInterval();
            for (int attempt = 1; attempt < maxAttempts; attempt++) {
                total = total.plus(interval);
                interval = min(scale(interval, backoff.multiplier()), backoff.maxInterval());
            }
            return total;
        }

        private static Duration scale(Duration duration, double factor) {
            return Duration.ofNanos((long) (duration.toNanos() * factor));
        }

        private static Duration min(Duration left, Duration right) {
            return left.compareTo(right) <= 0 ? left : right;
        }
    }

    /**
     * What is audited, and where.
     *
     * @param enabled whether anything is audited
     * @param persist whether events are written to {@code sync_audit_record} as well as to the log
     */
    public record AuditSettings(boolean enabled, boolean persist) {
    }

    /**
     * An asynchronous remote job's three schedules and its lifetime rules.
     *
     * @param submitSchedule      when the submit pass runs
     * @param maxConcurrentSubmits how many new jobs one submit pass may start
     * @param pollSchedule        when the poll pass runs
     * @param pollBackoffMultiplier how much a single job's probe interval grows while it keeps running
     * @param pollMaxInterval     ceiling on a single job's probe interval
     * @param honourRetryAfter    whether the partner's own polling guidance overrides the computed interval
     * @param collectSchedule     when the collect pass runs
     * @param collectConcurrency  how many results may be collected at once
     * @param maxLifetime         how long a job may live before it is cancelled and its demand requeued
     * @param onAmbiguousSubmit   what to do about a submit whose outcome is unknown
     * @param submitGracePeriod   how long a submit may be in progress before it counts as ambiguous
     */
    public record JobSettings(ScheduleSpec submitSchedule,
                              int maxConcurrentSubmits,
                              ScheduleSpec pollSchedule,
                              double pollBackoffMultiplier,
                              Duration pollMaxInterval,
                              boolean honourRetryAfter,
                              ScheduleSpec collectSchedule,
                              int collectConcurrency,
                              Duration maxLifetime,
                              AmbiguousSubmitPolicy onAmbiguousSubmit,
                              Duration submitGracePeriod) {
    }
}
