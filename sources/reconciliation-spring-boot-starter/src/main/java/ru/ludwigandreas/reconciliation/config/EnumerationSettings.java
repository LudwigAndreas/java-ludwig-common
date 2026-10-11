package ru.ludwigandreas.reconciliation.config;

import ru.ludwigandreas.job.core.schedule.ScheduleSpec;

import java.time.Duration;
import java.util.Optional;

/**
 * One enumeration's fully resolved settings: its own block, merged over
 * {@code ludwig.reconciliation.enumeration-defaults}, merged over the module's built-in values.
 *
 * <p>Immutable and complete - nothing downstream consults {@link ReconciliationProperties} or decides
 * what a missing value means. The merge happens once, in {@link EnumerationSettingsResolver}.
 *
 * @param name                    the enumeration's name
 * @param enabled                 whether it runs
 * @param schedule                when the walk pass runs
 * @param runTimeout              how long one run may hold the task's lease
 * @param discoverySchedule       when the discover pass runs
 * @param discoveryPageSize       scopes read per page of the scope source
 * @param fullInterval            how often a scope is walked in full
 * @param incrementalInterval     how often changes are fetched between full walks; null when the task
 *                                is not incremental
 * @param pageSize                items requested per page of the feed
 * @param maxConcurrency          scopes walked at once
 * @param maxScopesPerRun         the most scopes one run takes
 * @param sweepConfirmAfterMisses consecutive completed full walks that must omit a member
 * @param sweepMaxFraction        the largest share of a scope one sweep may report unconfirmed
 * @param retry                   a member's attempt budget and backoff
 * @param auditEnabled            whether walks and removals are audited
 * @param restClient              the named REST client, or null
 * @param rateLimit               the named rate limit, or null
 */
public record EnumerationSettings(String name,
                                  boolean enabled,
                                  ScheduleSpec schedule,
                                  Duration runTimeout,
                                  ScheduleSpec discoverySchedule,
                                  int discoveryPageSize,
                                  Duration fullInterval,
                                  Duration incrementalInterval,
                                  int pageSize,
                                  int maxConcurrency,
                                  int maxScopesPerRun,
                                  int sweepConfirmAfterMisses,
                                  double sweepMaxFraction,
                                  TaskSettings.RetrySettings retry,
                                  boolean auditEnabled,
                                  String restClient,
                                  String rateLimit) {

    /** Whether incremental walks are configured between full ones. */
    public boolean isIncremental() {
        return incrementalInterval != null;
    }

    /** The incremental interval, if this enumeration is incremental. */
    public Optional<Duration> incrementalIntervalOrEmpty() {
        return Optional.ofNullable(incrementalInterval);
    }

    /** The named REST client, if one is configured. */
    public Optional<String> restClientName() {
        return Optional.ofNullable(restClient);
    }

    /** The named rate limit, if one is configured. */
    public Optional<String> rateLimitName() {
        return Optional.ofNullable(rateLimit);
    }
}
