package ru.ludwigandreas.outbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Crash recovery for poller instances that claimed rows (flipped to PROCESSING) and died before
 * recording an outcome. {@link OutboxMessageRepository#reclaimStale} is a plain repository call, already
 * transactional via Spring Data's own repository proxy - no extra {@code @Transactional} needed here.
 */
public class OutboxStaleReclaimScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxStaleReclaimScheduler.class);

    private final OutboxMessageRepository repository;
    private final TaskScheduler taskScheduler;
    private final Duration staleTimeout;
    private final Duration fixedDelay;

    private volatile ScheduledFuture<?> scheduledFuture;

    public OutboxStaleReclaimScheduler(OutboxMessageRepository repository,
                                        TaskScheduler taskScheduler,
                                        Duration staleTimeout,
                                        Duration fixedDelay) {
        this.repository = repository;
        this.taskScheduler = taskScheduler;
        this.staleTimeout = staleTimeout;
        this.fixedDelay = fixedDelay;
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::reclaim, Instant.now().plus(fixedDelay), fixedDelay);
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
            int reclaimed = repository.reclaimStale(Instant.now().minus(staleTimeout));
            if (reclaimed > 0) {
                log.warn("Reclaimed {} stale PROCESSING outbox message(s) back to PENDING", reclaimed);
            }
        } catch (Exception e) {
            log.error("Outbox stale-reclaim cycle failed", e);
        }
    }
}
