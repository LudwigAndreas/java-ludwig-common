package ru.ludwigandreas.notification.service.metrics;

import java.time.Duration;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.Priority;

/**
 * The fallback, registered whenever no meter registry is on the classpath or metrics are switched
 * off.
 *
 * <p>Its point is that no call site anywhere has to be null-guarded. A nullable metrics collaborator
 * puts an {@code if} in front of every recording, and the one that gets forgotten is a
 * {@code NullPointerException} in the dispatch loop - a metrics bug that stops notifications.
 */
public class NoopNotificationMetrics implements NotificationMetrics {

    @Override
    public void recordRequestAccepted(String source) {
    }

    @Override
    public void recordRequestDuplicate(String source) {
    }

    @Override
    public void recordRequestRejected(String source) {
    }

    @Override
    public void recordDeliveryEnqueued(ChannelType channel, Priority priority) {
    }

    @Override
    public void recordDeliverySuppressed(ChannelType channel, String reason) {
    }

    @Override
    public void recordClaim(ChannelType channel, int claimed) {
    }

    @Override
    public void recordSendSucceeded(ChannelType channel) {
    }

    @Override
    public void recordSendFailed(ChannelType channel, String failureClass) {
    }

    @Override
    public void recordDeadLettered(ChannelType channel) {
    }

    @Override
    public void recordReceipt(ChannelType channel, String outcome) {
    }

    @Override
    public void recordSendDuration(ChannelType channel, Duration duration) {
    }

    @Override
    public void recordRenderDuration(ChannelType channel, Duration duration) {
    }

    @Override
    public void recordEndToEndLatency(ChannelType channel, Priority priority, Duration duration) {
    }

    @Override
    public void recordStaleReclaimed(long count) {
    }

    @Override
    public void recordQueueDepth(ChannelType channel, Priority priority, long depth) {
    }

    @Override
    public void recordOldestPendingAge(Duration age) {
    }

    @Override
    public void recordRateLimited(ChannelType channel) {
    }
}
