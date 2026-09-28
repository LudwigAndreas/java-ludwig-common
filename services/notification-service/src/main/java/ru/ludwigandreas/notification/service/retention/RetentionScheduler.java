package ru.ludwigandreas.notification.service.retention;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.notification.service.lock.LockNames;

/**
 * Runs the retention purge on one replica per schedule.
 *
 * <p>Under {@code job-core}'s leased {@link RunLock}, like the digest job and unlike the delivery
 * poller. Three replicas
 * purging concurrently would not corrupt anything - the statements are idempotent deletes - but they
 * would take conflicting locks on the same pages of the largest table in the service, at the worst
 * possible moment, and two of the three would do nothing but contend.
 *
 * <p>The lease is renewed between steps, and a lost lease stops the run. A purge is made of
 * independent statements, so stopping half way is safe: the remaining steps run on the next tick.
 *
 * <p>The lease length comes from {@code ludwig.job-core.lock.default-lease} rather than from a
 * constant here, so the deployment has one failover time rather than one per job.
 */
@Slf4j
public class RetentionScheduler implements SmartLifecycle {

    private final RunLock runLock;
    private final RetentionService retentionService;
    private final TaskScheduler taskScheduler;
    private final NotificationProperties properties;

    private volatile ScheduledFuture<?> scheduledFuture;

    public RetentionScheduler(RunLock runLock,
                              RetentionService retentionService,
                              TaskScheduler taskScheduler,
                              NotificationProperties properties) {
        this.runLock = runLock;
        this.retentionService = retentionService;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    @Override
    public void start() {
        Duration interval = properties.getRetention().getRunInterval();
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::run, Instant.now().plus(interval), interval);
        log.info("Retention purge scheduled every {}", interval);
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
            runLock.runIfAvailable(LockNames.RETENTION, this::purge);
        } catch (RuntimeException e) {
            log.error("Retention purge run failed", e);
        }
    }

    private void purge(RunLockHandle lock) {
        Instant now = Instant.now();
        // Order matters in one place only: content is dropped before the deliveries that own it, so
        // the cascade never has to do the work twice. The rest are independent.
        long content = step(lock, () -> retentionService.purgeContent(now));
        long scrubbed = step(lock, () -> retentionService.scrubRecipientData(now));
        long deliveries = step(lock, () -> retentionService.purgeDeliveries(now));
        long history = step(lock, () -> retentionService.purgeHistory(now));
        long suppressions = step(lock, () -> retentionService.compactSuppressions(now));
        // No idempotency step: that table and its purge belong to idempotency-spring-boot-starter, which
        // schedules its own run under the same lock mechanism. Two purges of one table on two schedules is
        // exactly the duplication the promotion removed.
        long windows = step(lock, () -> retentionService.purgeRateLimitWindows(now));

        if (content + scrubbed + deliveries + history + suppressions + windows > 0) {
            log.info("Retention purge: {} bodies dropped, {} deliveries scrubbed, {} deliveries "
                            + "deleted, {} history rows, {} suppressions, {} rate-limit windows",
                    content, scrubbed, deliveries, history, suppressions, windows);
        }
    }

    /** Runs one purge step, unless the lock has been lost since the previous one. */
    private long step(RunLockHandle lock, java.util.function.LongSupplier work) {
        if (!lock.renew(runLock.defaultLeaseTtl())) {
            log.warn("Retention purge stopped part way: the lock was lost");
            return 0L;
        }
        return work.getAsLong();
    }
}
