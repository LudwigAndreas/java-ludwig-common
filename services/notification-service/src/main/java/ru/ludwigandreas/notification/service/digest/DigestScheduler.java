package ru.ludwigandreas.notification.service.digest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.notification.service.lock.LockNames;

/**
 * Runs the digest collapse on one replica per schedule.
 *
 * <p>The whole body is inside {@link RunLock#runIfAvailable(String, java.util.function.Consumer)},
 * and on a three-replica deployment two of the three do nothing on every tick. That is the expected
 * outcome rather than a failure, and it is the reason the lock does not block: a queued acquisition
 * would make the job run three times in sequence, which is precisely what it was introduced to
 * prevent.
 *
 * <p>The lease length is not chosen here. It comes from {@code ludwig.job-core.lock.default-lease},
 * so the deployment's failover time is one number an operator can move during an incident rather
 * than a constant compiled into this class.
 *
 * <p>The lease is renewed between groups. A run that collapses two hundred groups can legitimately
 * outlive the default lease, and a job that outruns its lease without renewing has lost it - another
 * replica will have taken over and be collapsing the same groups. So a failed renewal stops the run
 * immediately, mid-list, and the remaining groups wait for the next tick. Renewing with the same TTL
 * the lease was taken on keeps that one number the only thing a deployment has to reason about.
 */
@Slf4j
public class DigestScheduler implements SmartLifecycle {

    private final RunLock runLock;
    private final DigestCollapseService collapseService;
    private final NotificationDeliveryRepository deliveryRepository;
    private final TaskScheduler taskScheduler;
    private final NotificationProperties properties;

    private volatile ScheduledFuture<?> scheduledFuture;

    public DigestScheduler(RunLock runLock,
                           DigestCollapseService collapseService,
                           NotificationDeliveryRepository deliveryRepository,
                           TaskScheduler taskScheduler,
                           NotificationProperties properties) {
        this.runLock = runLock;
        this.collapseService = collapseService;
        this.deliveryRepository = deliveryRepository;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    @Override
    public void start() {
        Duration interval = properties.getDigest().getRunInterval();
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::run, Instant.now().plus(interval), interval);
        log.info("Digest collapse scheduled every {}", interval);
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

    private void run() {
        try {
            runLock.runIfAvailable(LockNames.DIGEST, this::collapseDueGroups);
        } catch (RuntimeException e) {
            // A repeating scheduled task whose run throws is cancelled by the scheduler, which would
            // silently stop digests for the life of the pod.
            log.error("Digest collapse run failed", e);
        }
    }

    private void collapseDueGroups(RunLockHandle lock) {
        Instant now = Instant.now();
        List<String> groups = deliveryRepository.dueDigestGroups(
                now, properties.getDigest().getMaxGroupsPerRun());
        int collapsed = 0;
        for (String group : groups) {
            if (!lock.renew(runLock.defaultLeaseTtl())) {
                log.warn("Stopping the digest run after {} group(s): the lock was lost", collapsed);
                return;
            }
            if (collapseService.collapse(group, now).isPresent()) {
                collapsed++;
            }
        }
        if (collapsed > 0) {
            log.info("Collapsed {} digest group(s)", collapsed);
        }
    }
}
