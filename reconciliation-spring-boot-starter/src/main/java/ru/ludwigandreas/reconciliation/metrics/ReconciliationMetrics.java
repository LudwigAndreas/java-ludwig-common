package ru.ludwigandreas.reconciliation.metrics;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Instrumentation hook for everything this module does.
 *
 * <p>{@code NoopReconciliationMetrics} is always registered as a fallback, so nothing in the engine
 * has to null-check; {@code MicrometerReconciliationMetrics} replaces it when Micrometer is on the
 * classpath and {@code ludwig.reconciliation.metrics.enabled} is true.
 *
 * <h2>The two signals worth alerting on</h2>
 *
 * <ul>
 *   <li><b>{@code reconciliation.freshness.lag}</b> - the age of the oldest record whose external
 *       state has not been refreshed within its {@code freshness-target}. It is the only metric that
 *       describes the thing the module exists to do. Every other number here can look healthy while
 *       this one climbs: a task whose demand query stopped matching anything fetches nothing,
 *       succeeds instantly, and reports a perfect run rate forever.</li>
 *   <li><b>{@code reconciliation.quota.lease.reclaimed}</b> - any sustained non-zero value means
 *       either leases are leaking or remote work is being duplicated. Both are invisible in
 *       everything else, and both get worse on their own.</li>
 * </ul>
 */
public interface ReconciliationMetrics {

    /**
     * Records a completed run.
     *
     * @param task     task name
     * @param tier     {@code hot} or {@code cold}
     * @param outcome  how it ended
     * @param duration how long it took
     */
    void recordRun(String task, String tier, RunOutcome outcome, Duration duration);

    /**
     * Records records fetched from the partner in one run.
     *
     * @param task  task name
     * @param count how many
     */
    void recordFetched(String task, int count);

    /**
     * Records a per-record outcome.
     *
     * @param task    task name
     * @param outcome the settled status, lowercased - {@code applied}, {@code unchanged},
     *                {@code rejected}, {@code deferred}, {@code missing}, {@code failed},
     *                {@code quarantined}
     */
    void recordRecordOutcome(String task, String outcome);

    /**
     * Records a key the partner did not know.
     *
     * @param task   task name
     * @param policy the task's not-found policy, so the count can be read against what was done
     */
    void recordNotFound(String task, String policy);

    /**
     * Records how long one outbound fetch took.
     *
     * @param task     task name
     * @param shape    the fetch shape
     * @param duration how long it took
     */
    void recordFetchDuration(String task, String shape, Duration duration);

    /**
     * Records how long applying one staged record took.
     *
     * @param task     task name
     * @param duration how long it took
     */
    void recordApplyDuration(String task, Duration duration);

    /**
     * Registers the freshness-lag gauge for a task - the SLO metric.
     *
     * @param task     task name
     * @param lagSeconds age, in seconds, of the oldest record past its freshness target
     */
    void registerFreshnessLag(String task, Supplier<Number> lagSeconds);

    /**
     * Registers the staged-backlog gauges for a task.
     *
     * @param task            task name
     * @param backlogDepth    how many records are waiting to be applied
     * @param oldestStagedAge age in seconds of the oldest of them
     */
    void registerStagedBacklog(String task, Supplier<Number> backlogDepth, Supplier<Number> oldestStagedAge);

    /**
     * Registers the quarantined-record gauge for a task.
     *
     * @param task  task name
     * @param count how many records are quarantined
     */
    void registerQuarantined(String task, Supplier<Number> count);

    /**
     * Registers a quota's gauges.
     *
     * @param quota            quota name
     * @param inFlight         slots currently held
     * @param limit            slots configured
     * @param oldestWaiterAge  age in seconds of the longest-waiting task, or zero when nobody waits
     */
    void registerQuota(String quota, Supplier<Number> inFlight, Supplier<Number> limit,
                       Supplier<Number> oldestWaiterAge);

    /**
     * Records how long a task waited for a quota slot.
     *
     * @param quota    quota name
     * @param task     task name
     * @param acquired whether it got one
     * @param waited   how long it waited
     */
    void recordQuotaAcquire(String quota, String task, boolean acquired, Duration waited);

    /**
     * Records a lease taken back from a holder that stopped heartbeating.
     *
     * <p>Alert on any sustained non-zero value: it is the leading indicator of both quota leaks and
     * duplicate remote work.
     *
     * @param quota  quota name
     * @param policy the reclaim policy that was applied
     */
    void recordLeaseReclaimed(String quota, String policy);

    /**
     * Records a finished asynchronous job.
     *
     * @param task     task name
     * @param state    its terminal state
     * @param duration how long it lived
     * @param polls    how many times it was probed
     */
    void recordJobSettled(String task, String state, Duration duration, int polls);

    /**
     * Records a submit whose outcome could not be determined.
     *
     * @param task       task name
     * @param resolution how it was resolved - {@code adopted}, {@code orphaned} or {@code resubmitted}
     */
    void recordAmbiguousSubmit(String task, String resolution);
}
