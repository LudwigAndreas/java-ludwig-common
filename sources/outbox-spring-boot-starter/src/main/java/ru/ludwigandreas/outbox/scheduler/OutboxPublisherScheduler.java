package ru.ludwigandreas.outbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

import java.time.Duration;

/**
 * Drives {@link OutboxProcessingService#processBatch()} on a fixed delay.
 *
 * <p>Everything about <em>how</em> it is scheduled - an injected {@link TaskScheduler} rather than
 * {@code @Scheduled}, non-reentrancy, containing an exception so a bad cycle does not cancel the
 * schedule for the lifetime of the process, and draining an in-flight cycle on shutdown instead of
 * interrupting it mid-claim - lives in {@link SelfSchedulingLifecycle}, shared with the
 * reconciliation starter. What remains here is only the poll cycle itself.
 */
public class OutboxPublisherScheduler extends SelfSchedulingLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final OutboxProcessingService processingService;

    /**
     * Creates the poller.
     *
     * @param processingService the claim/dispatch/record cycle this job drives
     * @param taskScheduler     scheduler the job registers itself with
     * @param initialDelay      how long after startup the first cycle runs
     * @param fixedDelay        delay between the end of one cycle and the start of the next
     * @param drainTimeout      how long shutdown waits for an in-flight cycle to finish
     */
    public OutboxPublisherScheduler(OutboxProcessingService processingService,
                                    TaskScheduler taskScheduler,
                                    Duration initialDelay,
                                    Duration fixedDelay,
                                    Duration drainTimeout) {
        super("outbox-publisher", taskScheduler,
                ScheduleSpec.fixedDelay(fixedDelay, initialDelay), drainTimeout);
        this.processingService = processingService;
    }

    @Override
    protected void runOnce() {
        int claimed = processingService.processBatch();
        if (claimed > 0) {
            log.debug("Processed {} outbox message(s)", claimed);
        }
    }
}
