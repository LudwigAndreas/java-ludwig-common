package ru.ludwigandreas.notification.service.health;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.queue.QueueDepthReader;

/**
 * Reports the queue's backlog to the <em>readiness</em> group, never to liveness.
 *
 * <p>That split is the whole point, and getting it wrong is expensive. A queue that has backed up
 * because a provider is down describes a service that is perfectly alive: the process is healthy, the
 * database is reachable, the ingress is still accepting requests. Wiring the backlog to liveness would
 * have Kubernetes restart the pod, which cannot possibly fix the provider, and would restart the
 * other two replicas in turn - turning a partner's outage into ours. The observability starter splits
 * the two probes; this indicator is registered into the readiness group only.
 *
 * <p>What readiness-down actually achieves is narrower and real: the pod stops receiving REST traffic
 * while it is visibly not keeping up, so the load balancer prefers replicas that are. It keeps
 * consuming from Kafka and keeps draining the queue throughout, because readiness governs inbound
 * HTTP and nothing else.
 *
 * <p>The signal is the age of the oldest waiting delivery rather than the depth. Depth is a number
 * without a scale - fifty rows is nothing during a campaign and alarming at three in the morning -
 * whereas an oldest-pending age above the threshold means the queue is not moving, at any volume.
 */
@Component
@RequiredArgsConstructor
public class NotificationQueueHealthIndicator implements HealthIndicator {

    private final QueueDepthReader depthReader;
    private final NotificationProperties properties;

    @Override
    public Health health() {
        Duration threshold = properties.getQueue().getReadinessMaxPendingAge();
        try {
            Duration oldest = depthReader.oldestPendingAge();
            Health.Builder builder = oldest.compareTo(threshold) > 0 ? Health.down() : Health.up();
            return builder
                    .withDetail("oldestPendingAgeSeconds", oldest.toSeconds())
                    .withDetail("thresholdSeconds", threshold.toSeconds())
                    .build();
        } catch (RuntimeException e) {
            // A database this indicator cannot reach is genuinely not ready, and saying so is more
            // useful than an exception propagating out of the actuator endpoint as a 500 with a
            // stack trace in it.
            return Health.down(e).withDetail("reason", "queue depth is unreadable").build();
        }
    }
}
