package ru.ludwigandreas.audit.store.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.audit.AuditActions;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.audit.config.AuditProperties;
import ru.ludwigandreas.audit.store.metrics.AuditMetrics;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Removes events whose retention has elapsed, and records having done it.
 *
 * <p>Modelled on {@code ExportRetentionPurge}, which is the platform's existing shape for this: a
 * {@code job-core} {@link SelfSchedulingLifecycle} under a leased {@link RunLock}, so every replica runs
 * the schedule and exactly one of them does the work, and an instance that dies mid-purge does not stop
 * the next one for good.
 *
 * <h2>Why retention is not optional, and why it is long</h2>
 *
 * <p>An audit trail with no retention is a growing, unindexed copy of everything the platform has ever
 * done, held past any basis for holding it - which is its own disclosure risk, and a personal value in it
 * outlives every erasure request that was meant to remove it. The defaults are measured in years rather
 * than days because retention here is a compliance figure and not a disk-space one.
 *
 * <h2>The purge audits itself</h2>
 *
 * <p>Every pass that removed anything writes an {@link AuditActions#PURGED} event saying which category,
 * what cutoff, and how many rows. An audit trail that can be silently shortened is not a trail: without
 * this row, "nothing happened in that window" and "something removed the window" are the same
 * observation.
 *
 * <p>Each pass is bounded and committed on its own: the table is on the write path of every audited
 * operation, and an unbounded delete over a year of rows takes a long lock and a large amount of WAL while
 * that keeps happening. The transaction is declared on the repository statement rather than here, because
 * a {@code @Transactional} method called from another method of this same class is a self-invocation the
 * proxy never sees - which is exactly how the first version of this job failed with
 * {@code TransactionRequiredException} on every run.
 *
 * <p>The event is emitted <em>after</em> the batch commits and carries the count the pass actually
 * removed. Emitting it first would record a purge that a failure then rolled back, and the
 * {@code audit.purged} row is the one row in the table nobody can cross-check against anything else.
 */
@Slf4j
public class AuditRetentionPurge extends SelfSchedulingLifecycle {

    /** The name this job's log lines and its lock carry. */
    public static final String JOB_NAME = "ludwig-audit-retention-purge";

    private final AuditEventRepository repository;
    private final AuditProperties properties;
    private final RunLock lock;
    private final AuditSink sink;
    private final AuditMetrics metrics;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param taskScheduler the scheduler this job registers itself with
     * @param repository    the trail
     * @param properties    the retention configuration
     * @param lock          the platform's distributed lock
     * @param sink          where the {@code audit.purged} event goes - the same sink as everything else,
     *                      so the purge is recorded wherever the trail is
     * @param metrics       the purge counter, or {@code null} without Micrometer
     * @param clock         supplies the cutoff
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuditRetentionPurge(TaskScheduler taskScheduler, AuditEventRepository repository,
                              AuditProperties properties, RunLock lock, AuditSink sink,
                              AuditMetrics metrics, Clock clock) {
        super(JOB_NAME, taskScheduler,
                ScheduleSpec.fixedDelay(properties.getRetention().getInterval()),
                properties.getRetention().getDrainTimeout());
        this.repository = repository;
        this.properties = properties;
        this.lock = lock;
        this.sink = sink;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        lock.tryAcquire(JOB_NAME, properties.getRetention().getInterval().multipliedBy(2))
                .ifPresent(this::purgeUnder);
    }

    private void purgeUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            purgeAllCategories();
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - the handle's close is the only thing that can throw here
            // that runOnce has not already let out, and failing to release a leased lock is not worth
            // failing the job over because the lease expires on its own.
            log.warn("Could not release the audit retention purge lock: {}", e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }

    /**
     * One pass per configured retention period.
     *
     * <p>Per category rather than one cutoff for the table, because the whole point of per-category
     * retention is that an access-denial record and a consent decision do not have the same legal basis.
     *
     * <p>Everything a deployment did <em>not</em> give its own period is then purged in one further pass, as
     * the complement of the overridden categories rather than as a walk over the platform's own nine.
     * {@code AuditEvent.category} is deliberately open - a service audits its own domain under a category
     * this module has never heard of - so enumerating would silently never expire those rows, and it would
     * do so only from the moment a deployment configured its first per-category override, which is exactly
     * when nobody would be looking for a retention hole.
     */
    private void purgeAllCategories() {
        Map<String, Period> overrides = properties.getRetention().getByCategory();
        for (Map.Entry<String, Period> entry : overrides.entrySet()) {
            purgeCategory(entry.getKey(), entry.getValue());
        }
        purgeEverythingElse(new LinkedHashSet<>(overrides.keySet()));
    }

    private void purgeCategory(String category, Period period) {
        Instant cutoff = cutoffFor(period);
        if (cutoff == null) {
            return;
        }
        purge(category, cutoff, repository.countOlderThan(category, cutoff),
                batch -> repository.purgeOlderThan(category, cutoff, batch));
    }

    private void purgeEverythingElse(Set<String> overridden) {
        Instant cutoff = cutoffFor(properties.getRetention().getDefaultPeriod());
        if (cutoff == null) {
            return;
        }
        purge(null, cutoff, repository.countOlderThanExcluding(overridden, cutoff),
                batch -> repository.purgeOlderThanExcluding(overridden, cutoff, batch));
    }

    /** The cutoff for a period, or {@code null} when the period does not describe a usable one. */
    private Instant cutoffFor(Period period) {
        if (period == null || period.isZero() || period.isNegative()) {
            // "No retention configured" must never mean "keep nothing".
            return null;
        }
        return clock.instant().atZone(ZoneOffset.UTC).minus(period).toInstant();
    }

    private void purge(String category, Instant cutoff, long expired, IntUnaryOperator purge) {
        if (expired == 0) {
            return;
        }
        int removed = purge.applyAsInt(properties.getRetention().getBatchSize());
        if (removed == 0) {
            return;
        }
        if (metrics != null) {
            metrics.purged(category, removed);
        }
        log.info("Audit retention purge removed {} event(s) from category {} older than {} ({} expired)",
                removed, category == null ? "all others" : category, cutoff, expired);
        recordPurge(category, cutoff, removed, expired);
    }

    private void recordPurge(String category, Instant cutoff, int removed, long expired) {
        sink.record(AuditEvent.builder()
                .category(AuditCategories.AUDIT)
                .action(AuditActions.PURGED)
                .occurredAt(clock.instant())
                .resource(Resource.ofType(category == null ? "audit-event" : category))
                // PARTIAL when the batch did not reach everything that had expired: the next pass will,
                // and a reader of this row must be able to tell "the window is now clear" from "some of
                // the window is still there".
                .outcome(removed < expired
                        ? AuditOutcome.partial(removed + " of " + expired + " expired events removed")
                        : AuditOutcome.success())
                .attributes(purgeAttributes(category, cutoff, removed, expired))
                .build());
    }

    private Map<String, Object> purgeAttributes(String category, Instant cutoff, int removed, long expired) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("category", category == null ? "all-others" : category);
        attributes.put("cutoff", cutoff.toString());
        attributes.put("removed", removed);
        attributes.put("expired", expired);
        return attributes;
    }

}
