package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;

/**
 * Returns deliveries whose lease was never released back to the queue.
 *
 * <p>The graceful-shutdown drain handles an orderly stop. This handles everything else: a
 * {@code SIGKILL}, an OOM kill, a node that vanished. Without it, a pod that dies between claiming a
 * batch and recording its outcomes strands those deliveries {@code CLAIMED} forever, and nobody finds
 * out except the recipients who never heard from us.
 *
 * <p>Deliberately does not touch the attempt counter. A pod that died before dispatching made no
 * attempt, and counting one would spend a retry on an infrastructure failure that has nothing to do
 * with the recipient - so a pod crash-looping through a deploy could exhaust a delivery's whole
 * budget without ever having contacted a provider.
 *
 * <h2>Behaviour at three replicas</h2>
 *
 * <p>All three run this, and that is fine and needs no lock. The statement is a conditional bulk
 * update: whichever replica runs first reclaims the rows, and the others match nothing because the
 * predicate no longer holds. Three harmless statements a minute is cheaper than the coordination that
 * would avoid two of them.
 *
 * <p>The lease timeout is the one number that must be right. Shorter than the slowest legitimate
 * dispatch of a full batch and this sweeper will reclaim rows a healthy pod is still working on -
 * which sends them twice. {@code NotificationQueueConfig} checks the relationship at startup.
 */
@Slf4j
public class StaleLeaseReclaimScheduler implements SmartLifecycle {

    private final DeliveryClaimService claimService;
    private final TaskScheduler taskScheduler;
    private final NotificationMetrics metrics;
    private final Duration leaseTimeout;
    private final Duration interval;

    private volatile ScheduledFuture<?> scheduledFuture;

    public StaleLeaseReclaimScheduler(DeliveryClaimService claimService,
                                      TaskScheduler taskScheduler,
                                      NotificationMetrics metrics,
                                      Duration leaseTimeout,
                                      Duration interval) {
        this.claimService = claimService;
        this.taskScheduler = taskScheduler;
        this.metrics = metrics;
        this.leaseTimeout = leaseTimeout;
        this.interval = interval;
    }

    @Override
    public void start() {
        // First run one interval in, not immediately: at startup every other replica's legitimately
        // held leases are as old as they are going to look, and there is nothing to gain from
        // examining them before this instance has settled.
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::reclaim, Instant.now().plus(interval), interval);
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

    private void reclaim() {
        try {
            long reclaimed = claimService.reclaimStale(Instant.now().minus(leaseTimeout));
            if (reclaimed > 0) {
                metrics.recordStaleReclaimed(reclaimed);
                // WARN rather than INFO: a non-zero count means an instance died holding work, which
                // is worth noticing even though the system just recovered from it.
                log.warn("Reclaimed {} stale delivery lease(s) older than {}", reclaimed, leaseTimeout);
            }
        } catch (RuntimeException e) {
            log.error("Stale-lease reclaim cycle failed", e);
        }
    }
}
