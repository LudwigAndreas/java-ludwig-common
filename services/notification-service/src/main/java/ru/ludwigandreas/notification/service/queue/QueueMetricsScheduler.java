package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.repository.query.QueueDepth;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.Priority;

/**
 * Keeps the two SLO gauges current: queue depth per lane, and the age of the oldest waiting delivery.
 *
 * <p>These are the numbers worth paging on. Throughput counters describe what happened; depth and age
 * describe whether the queue is keeping up. A queue holding fifty rows that are four seconds old is
 * healthy, and one holding fifty rows that are an hour old is an outage - the counters cannot tell
 * those apart, and the depth alone cannot either.
 *
 * <h2>Why a poll rather than a Micrometer gauge over a supplier</h2>
 *
 * <p>Registering a gauge whose value function runs a {@code COUNT} would execute two aggregate
 * queries on every scrape, from every replica, on a schedule this service does not control - and a
 * Prometheus deployment that added a second scraper would silently double that load. A poll on a
 * configured interval keeps the cost known.
 *
 * <p>Every lane is published on every cycle, including the empty ones. A gauge that simply stops
 * being reported when its queue drains looks identical, on a dashboard, to a gauge whose exporter
 * died - so a lane that is empty publishes a zero.
 */
@Slf4j
public class QueueMetricsScheduler implements SmartLifecycle {

    private final QueueDepthReader depthReader;
    private final TaskScheduler taskScheduler;
    private final NotificationMetrics metrics;
    private final Duration interval;

    private volatile ScheduledFuture<?> scheduledFuture;

    public QueueMetricsScheduler(QueueDepthReader depthReader,
                                 TaskScheduler taskScheduler,
                                 NotificationMetrics metrics,
                                 Duration interval) {
        this.depthReader = depthReader;
        this.taskScheduler = taskScheduler;
        this.metrics = metrics;
        this.interval = interval;
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::publish, Instant.now().plus(interval), interval);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null) {
            future.cancel(false);
        }
        scheduledFuture = null;
    }

    @Override
    public boolean isRunning() {
        ScheduledFuture<?> future = scheduledFuture;
        return future != null && !future.isDone();
    }

    private void publish() {
        try {
            List<QueueDepth> depths = depthReader.queueDepth();
            Set<String> reported = new HashSet<>();
            for (QueueDepth depth : depths) {
                ChannelType channel = ChannelType.valueOf(depth.channel().name());
                Priority priority = Priority.valueOf(depth.priority().name());
                metrics.recordQueueDepth(channel, priority, depth.count());
                reported.add(channel + "/" + priority);
            }
            // Lanes with nothing in them publish a zero, so an empty queue is distinguishable from
            // an exporter that has stopped reporting.
            for (ChannelType channel : EnumSet.allOf(ChannelType.class)) {
                for (Priority priority : EnumSet.allOf(Priority.class)) {
                    if (!reported.contains(channel + "/" + priority)) {
                        metrics.recordQueueDepth(channel, priority, 0L);
                    }
                }
            }
            metrics.recordOldestPendingAge(depthReader.oldestPendingAge());
        } catch (RuntimeException e) {
            log.warn("Could not refresh queue metrics", e);
        }
    }
}
