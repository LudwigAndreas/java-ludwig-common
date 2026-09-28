package ru.ludwigandreas.outbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

import java.time.Duration;
import java.time.Instant;

/**
 * Crash recovery for poller instances that claimed rows (flipped them to {@code PROCESSING}) and died
 * before recording an outcome.
 *
 * <p>{@link OutboxMessageRepository#reclaimStale} carries its own {@code @Transactional}: a
 * {@code @Modifying} query declared on a repository interface is NOT transactional by default - only
 * the CRUD methods {@code SimpleJpaRepository} implements inherit its attributes - so the annotation
 * is on the repository method rather than here, where a {@code @Transactional} on a plain bean that
 * is not proxied for transactions would do nothing at all.
 */
public class OutboxStaleReclaimScheduler extends SelfSchedulingLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxStaleReclaimScheduler.class);

    private final OutboxMessageRepository repository;
    private final Duration staleTimeout;

    /**
     * Creates the reclaimer.
     *
     * @param repository    repository holding the reclaim statement
     * @param taskScheduler scheduler the job registers itself with
     * @param staleTimeout  how long a row may sit {@code PROCESSING} before it is presumed abandoned
     * @param fixedDelay    delay between sweeps
     * @param drainTimeout  how long shutdown waits for an in-flight sweep to finish
     */
    public OutboxStaleReclaimScheduler(OutboxMessageRepository repository,
                                       TaskScheduler taskScheduler,
                                       Duration staleTimeout,
                                       Duration fixedDelay,
                                       Duration drainTimeout) {
        super("outbox-stale-reclaim", taskScheduler, ScheduleSpec.fixedDelay(fixedDelay), drainTimeout);
        this.repository = repository;
        this.staleTimeout = staleTimeout;
    }

    @Override
    protected void runOnce() {
        int reclaimed = repository.reclaimStale(Instant.now().minus(staleTimeout));
        if (reclaimed > 0) {
            log.warn("Reclaimed {} stale PROCESSING outbox message(s) back to PENDING", reclaimed);
        }
    }
}
