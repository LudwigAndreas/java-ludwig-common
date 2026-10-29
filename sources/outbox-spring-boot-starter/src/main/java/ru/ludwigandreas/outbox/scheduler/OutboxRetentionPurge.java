package ru.ludwigandreas.outbox.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.outbox.audit.OutboxTransitionAudit;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

/**
 * Deletes terminal messages, with their history, once their retention has elapsed, and records every deletion.
 *
 * <p>The platform's existing purge shape, as {@code AuditRetentionPurge} and {@code ExportRetentionPurge} have it:
 * a {@code job-core} {@link SelfSchedulingLifecycle} under a leased {@link RunLock}, so every replica runs the
 * schedule and exactly one does the work, and one that dies mid-purge does not stop the next for good.
 *
 * <p>Each batch is bounded and committed on its own - {@code outbox_message} is on the write path of every
 * publishing transaction, and an unbounded delete over a week of rows holds locks and writes WAL while that keeps
 * happening. A pass repeats batches until one comes back short or half the lease is spent; whatever is left waits for
 * the next run.
 *
 * <p>Each batch that deleted anything is audited <em>after</em> it commits, with the count it actually deleted, and
 * as {@code PARTIAL} while expired messages remain: emitting first would record a deletion that a failure then rolled
 * back, and a reader must be able to tell "the window is clear" from "some of it is still there".
 */
public class OutboxRetentionPurge extends SelfSchedulingLifecycle {

    /** The name this job's log lines, its lock and the jobs listing carry. */
    public static final String JOB_NAME = "ludwig-outbox-retention-purge";

    /** The audit action of a purge batch. */
    public static final String PURGED = "outbox.purged";

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionPurge.class);

    private final OutboxMessageRepository repository;
    private final OutboxProperties.Retention retention;
    private final RunLock lock;
    private final AuditSink audit;
    private final OutboxMetrics metrics;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param taskScheduler the scheduler this job registers itself with
     * @param repository    the outbox table
     * @param retention     the retention settings, already validated
     * @param lock          the platform's distributed lock
     * @param audit         where each batch's {@code outbox.purged} event goes
     * @param metrics       the purge counter
     * @param clock         supplies the cutoffs
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a bean assembled in one @Bean method, each argument its own bean.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public OutboxRetentionPurge(TaskScheduler taskScheduler, OutboxMessageRepository repository,
                                OutboxProperties.Retention retention, RunLock lock, AuditSink audit,
                                OutboxMetrics metrics, Clock clock) {
        super(JOB_NAME, taskScheduler, ScheduleSpec.fixedDelay(retention.getInterval()), retention.getDrainTimeout());
        this.repository = repository;
        this.retention = retention;
        this.lock = lock;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        Duration lease = retention.getInterval().multipliedBy(2);
        lock.tryAcquire(JOB_NAME, lease).ifPresent(handle -> purgeUnder(handle, lease));
    }

    private void purgeUnder(RunLockHandle handle, Duration lease) {
        Instant giveUpAt = clock.instant().plus(lease.dividedBy(2));
        try (RunLockHandle held = handle) {
            Instant now = clock.instant();
            purge(OutboxStatus.PUBLISHED, now.minus(retention.getPublished()), giveUpAt);
            purge(OutboxStatus.DEAD_LETTER, now.minus(retention.getTerminal()), giveUpAt);
            purge(OutboxStatus.DISCARDED, now.minus(retention.getTerminal()), giveUpAt);
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - only the handle's close can throw here that the passes have not already
            // let out, and failing to release a leased lock is not worth failing the job over: the lease expires.
            log.warn("Could not release the outbox retention purge lock: {}", e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }

    private void purge(OutboxStatus status, Instant cutoff, Instant giveUpAt) {
        int batchSize = retention.getBatchSize();
        int deleted;
        do {
            deleted = repository.purgeFinishedBefore(status, cutoff, batchSize);
            if (deleted > 0) {
                long remaining = repository.countByStatusAndFinishedAtBefore(status, cutoff);
                metrics.recordPurged(status.name(), deleted);
                log.info("Outbox retention purge deleted {} {} message(s) finished before {} ({} remaining)",
                        deleted, status, cutoff, remaining);
                record(status, cutoff, deleted, remaining);
            }
        } while (deleted == batchSize && clock.instant().isBefore(giveUpAt));
    }

    private void record(OutboxStatus status, Instant cutoff, int deleted, long remaining) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("status", status.name());
        attributes.put("cutoff", cutoff.toString());
        attributes.put("count", deleted);
        attributes.put("remaining", remaining);
        audit.record(AuditEvent.builder()
                .category(AuditCategories.OUTBOX)
                .action(PURGED)
                .occurredAt(clock.instant())
                .resource(Resource.ofType(OutboxTransitionAudit.RESOURCE_TYPE))
                .outcome(remaining > 0
                        ? AuditOutcome.partial(deleted + " deleted, " + remaining + " expired still to purge")
                        : AuditOutcome.success())
                .attributes(attributes)
                .build());
    }
}
