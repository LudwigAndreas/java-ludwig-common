package ru.ludwigandreas.notification.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.Priority;

/**
 * Publishes {@link NotificationMetrics} to a Micrometer registry.
 *
 * <p>Gauges are the interesting part. A Micrometer gauge is a <em>reference</em> that the registry
 * polls on scrape, not a value that is pushed, so a queue-depth gauge cannot simply be "set". The
 * pattern here is the standard one: an {@link AtomicLong} per tag combination, registered once and
 * updated in place, which is why the maps below exist and why they are the only mutable state in the
 * class. They are bounded by {@code channels x priorities} - twelve entries at most - so the
 * cardinality concern that governs the rest of this class does not apply to them.
 *
 * <p>Registering a fresh gauge on each update instead would leak a meter per update and, worse,
 * would produce a scrape in which the same series appears many times with different values.
 */
public class MicrometerNotificationMetrics implements NotificationMetrics {

    private static final String QUEUE_DEPTH = "notification.queue.depth";
    private static final String OLDEST_PENDING = "notification.queue.oldest.pending.age";
    private static final String REQUESTS = "notification.requests";
    private static final String DELIVERIES = "notification.deliveries";
    private static final String SENDS = "notification.sends";
    private static final String RECEIPTS = "notification.receipts";
    private static final String CLAIMS = "notification.queue.claims";
    private static final String RECLAIMED = "notification.queue.reclaimed";
    private static final String RATE_LIMITED = "notification.queue.rate.limited";
    private static final String LOCKS = "notification.locks";
    private static final String SEND_DURATION = "notification.send.duration";
    private static final String RENDER_DURATION = "notification.render.duration";
    private static final String END_TO_END = "notification.delivery.latency";

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> queueDepthGauges = new ConcurrentHashMap<>();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public MicrometerNotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge(OLDEST_PENDING, oldestPendingAgeSeconds, AtomicLong::doubleValue);
    }

    @Override
    public void recordRequestAccepted(String source) {
        count(REQUESTS, Tags.of("source", source, "outcome", "accepted"));
    }

    @Override
    public void recordRequestDuplicate(String source) {
        count(REQUESTS, Tags.of("source", source, "outcome", "duplicate"));
    }

    @Override
    public void recordRequestRejected(String source) {
        count(REQUESTS, Tags.of("source", source, "outcome", "rejected"));
    }

    @Override
    public void recordDeliveryEnqueued(ChannelType channel, Priority priority) {
        count(DELIVERIES, Tags.of("channel", name(channel), "priority", name(priority),
                "outcome", "enqueued"));
    }

    @Override
    public void recordDeliverySuppressed(ChannelType channel, String reason) {
        // The reason is a closed set of internal codes (opt-out, quiet-hours, suppression-list,
        // channel-disabled, unresolvable), never provider text - which would be unbounded.
        count(DELIVERIES, Tags.of("channel", name(channel), "outcome", "suppressed", "reason", reason));
    }

    @Override
    public void recordClaim(ChannelType channel, int claimed) {
        registry.counter(CLAIMS, Tags.of("channel", name(channel))).increment(claimed);
    }

    @Override
    public void recordSendSucceeded(ChannelType channel) {
        count(SENDS, Tags.of("channel", name(channel), "outcome", "sent"));
    }

    @Override
    public void recordSendFailed(ChannelType channel, String failureClass) {
        count(SENDS, Tags.of("channel", name(channel), "outcome", "failed", "class", failureClass));
    }

    @Override
    public void recordDeadLettered(ChannelType channel) {
        count(SENDS, Tags.of("channel", name(channel), "outcome", "dead"));
    }

    @Override
    public void recordReceipt(ChannelType channel, String outcome) {
        count(RECEIPTS, Tags.of("channel", name(channel), "outcome", outcome));
    }

    @Override
    public void recordSendDuration(ChannelType channel, Duration duration) {
        timer(SEND_DURATION, Tags.of("channel", name(channel))).record(duration);
    }

    @Override
    public void recordRenderDuration(ChannelType channel, Duration duration) {
        timer(RENDER_DURATION, Tags.of("channel", name(channel))).record(duration);
    }

    @Override
    public void recordEndToEndLatency(ChannelType channel, Priority priority, Duration duration) {
        timer(END_TO_END, Tags.of("channel", name(channel), "priority", name(priority))).record(duration);
    }

    @Override
    public void recordStaleReclaimed(long count) {
        registry.counter(RECLAIMED).increment(count);
    }

    @Override
    public void recordQueueDepth(ChannelType channel, Priority priority, long depth) {
        String channelName = name(channel);
        String priorityName = name(priority);
        queueDepthGauges.computeIfAbsent(channelName + "/" + priorityName, key -> {
            AtomicLong holder = new AtomicLong();
            registry.gauge(QUEUE_DEPTH, Tags.of("channel", channelName, "priority", priorityName),
                    holder, AtomicLong::doubleValue);
            return holder;
        }).set(depth);
    }

    @Override
    public void recordOldestPendingAge(Duration age) {
        oldestPendingAgeSeconds.set(age == null ? 0L : Math.max(0L, age.getSeconds()));
    }

    @Override
    public void recordRateLimited(ChannelType channel) {
        count(RATE_LIMITED, Tags.of("channel", name(channel)));
    }

    @Override
    public void recordLockAcquisition(String lockName, boolean acquired) {
        count(LOCKS, Tags.of("lock", lockName, "outcome", acquired ? "acquired" : "contended"));
    }

    private void count(String name, Tags tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private Timer timer(String name, Tags tags) {
        return Timer.builder(name)
                .tags(tags)
                // Percentile histograms rather than client-side percentiles: a pre-computed p99 per
                // replica cannot be aggregated across replicas, so the estate-wide number would be an
                // average of percentiles, which is not a percentile of anything.
                .publishPercentileHistogram()
                .register(registry);
    }

    private static String name(Enum<?> value) {
        return value == null ? "unknown" : value.name();
    }
}
