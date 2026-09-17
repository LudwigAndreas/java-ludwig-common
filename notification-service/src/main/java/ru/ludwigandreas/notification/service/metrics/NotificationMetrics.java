package ru.ludwigandreas.notification.service.metrics;

import java.time.Duration;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.Priority;

/**
 * Instrumentation hook for the whole notification lifecycle, matching the pattern every other module
 * in this repository uses: an interface, a {@link NoopNotificationMetrics} always registered as a
 * fallback, and a Micrometer implementation that replaces it when a registry is present.
 *
 * <h2>Tag cardinality is bounded by construction</h2>
 *
 * <p>Nothing here takes a recipient, an address, a template variable or a delivery id. Those are the
 * four obvious things to want on a metric and each of them is unbounded: one tag valued by recipient
 * turns a campaign to a million people into a million time series, and a Micrometer registry that
 * large exhausts the heap before anybody notices the dashboards got slow. Channel, priority, outcome
 * and reason are all small closed sets.
 *
 * <p>The template key is deliberately excluded too. It is bounded in principle - there are only so
 * many templates - but it is bounded by a directory somebody can add files to without touching this
 * code, which is not a bound at all. Per-template success rates belong in a log-derived dashboard.
 *
 * <h2>The two that matter</h2>
 *
 * <p>{@link #recordQueueDepth} and {@link #recordOldestPendingAge} are the SLO signals. Everything
 * else describes throughput, which tells you what happened; those two tell you whether the queue is
 * keeping up, which is the only question worth paging somebody about.
 */
public interface NotificationMetrics {

    /** A request accepted at the ingress, before fan-out. */
    void recordRequestAccepted(String source);

    /** A request the dedup table recognised - the count that tells you redelivery is working. */
    void recordRequestDuplicate(String source);

    /** A request that produced no deliveries at all. */
    void recordRequestRejected(String source);

    /** One delivery row created by fan-out. */
    void recordDeliveryEnqueued(ChannelType channel, Priority priority);

    /** A delivery settled before it ever reached the queue, with the reason it was suppressed. */
    void recordDeliverySuppressed(ChannelType channel, String reason);

    /** A batch leased from the queue; {@code claimed} may be fewer than asked for. */
    void recordClaim(ChannelType channel, int claimed);

    /** A provider accepted a message. */
    void recordSendSucceeded(ChannelType channel);

    /**
     * A provider did not.
     *
     * @param failureClass {@code RETRYABLE} or {@code TERMINAL} - the ratio between them is what
     *                     distinguishes a provider outage from a bad recipient list
     */
    void recordSendFailed(ChannelType channel, String failureClass);

    /** Retries exhausted, or a terminal failure on the first attempt. */
    void recordDeadLettered(ChannelType channel);

    /** A provider receipt advanced a delivery. */
    void recordReceipt(ChannelType channel, String outcome);

    /** Time spent inside the channel's own send call, provider latency included. */
    void recordSendDuration(ChannelType channel, Duration duration);

    /** Time spent rendering, which is local and should stay small. */
    void recordRenderDuration(ChannelType channel, Duration duration);

    /**
     * End-to-end latency from the delivery row being created to the provider accepting it.
     *
     * <p>The number a delivery-time SLA is actually written against: it includes the queue wait and
     * the retry backoff, which is where the time goes, and {@link #recordSendDuration} does not.
     */
    void recordEndToEndLatency(ChannelType channel, Priority priority, Duration duration);

    /** Leases recovered from an instance that died holding them. Non-zero means a pod was killed. */
    void recordStaleReclaimed(long count);

    /** Deliveries waiting in one channel's lane. */
    void recordQueueDepth(ChannelType channel, Priority priority, long depth);

    /** Age of the oldest claimable delivery. Flat and rising means the queue has stopped. */
    void recordOldestPendingAge(Duration age);

    /** A poll cycle that claimed nothing because the channel's rate-limit window was exhausted. */
    void recordRateLimited(ChannelType channel);

    /** A maintenance job's attempt to take the distributed lock. */
    void recordLockAcquisition(String lockName, boolean acquired);
}
