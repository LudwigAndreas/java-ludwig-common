package ru.ludwigandreas.outbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Drives {@link OutboxProcessingService#processBatch()} on a fixed delay. Deliberately schedules itself
 * against an injected {@link TaskScheduler} rather than using {@code @Scheduled} (which would require
 * either SpEL/placeholder-string duration attributes re-parsed from {@link ru.ludwigandreas.outbox.config.OutboxProperties}'
 * already-bound {@link Duration} values, or the consuming application enabling {@code @EnableScheduling}
 * itself) - this keeps the module fully self-contained and plug-and-play.
 */
public class OutboxPublisherScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final OutboxProcessingService processingService;
    private final TaskScheduler taskScheduler;
    private final Duration initialDelay;
    private final Duration fixedDelay;

    private volatile ScheduledFuture<?> scheduledFuture;

    public OutboxPublisherScheduler(OutboxProcessingService processingService,
                                     TaskScheduler taskScheduler,
                                     Duration initialDelay,
                                     Duration fixedDelay) {
        this.processingService = processingService;
        this.taskScheduler = taskScheduler;
        this.initialDelay = initialDelay;
        this.fixedDelay = fixedDelay;
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::poll, Instant.now().plus(initialDelay), fixedDelay);
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

    private void poll() {
        try {
            int claimed = processingService.processBatch();
            if (claimed > 0) {
                log.debug("Processed {} outbox message(s)", claimed);
            }
        } catch (Exception e) {
            log.error("Outbox poll cycle failed", e);
        }
    }
}
