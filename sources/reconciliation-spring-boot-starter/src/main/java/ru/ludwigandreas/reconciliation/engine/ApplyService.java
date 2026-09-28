package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One apply pass: claim a batch of due staged records, and apply each on its own.
 *
 * <p>Claiming and applying are separate transactions, for the same reason they are in the outbox: the
 * claim must be committed and visible before the work starts, or a second instance claims the same
 * rows; and each record's outcome must commit independently, or the batch is back to being one
 * transaction and one bad record poisons it.
 *
 * <p>The pass runs on its own schedule, separate from the fetch. That independence is one of the main
 * things staging buys - a task can poll a partner every five minutes and still drain a backlog of
 * deferred records every ten seconds - and it is why this is a pass of its own rather than the tail of
 * the fetch run.
 */
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    private final SyncInboxRecordRepository repository;
    private final RecordApplier applier;
    private final ReconciliationMetrics metrics;
    private final String owner;

    /**
     * Creates the service.
     *
     * @param repository the staging table
     * @param applier    applies one record, in its own transaction
     * @param metrics    instrumentation
     * @param owner      this instance's identity, written into {@code locked_by}
     */
    public ApplyService(SyncInboxRecordRepository repository,
                        RecordApplier applier,
                        ReconciliationMetrics metrics,
                        String owner) {
        this.repository = repository;
        this.applier = applier;
        this.metrics = metrics;
        this.owner = owner;
    }

    /**
     * Runs one pass for a task.
     *
     * @param task the task
     * @return how many records were applied
     */
    public int runOnce(RegisteredTask<?, ?, ?> task) {
        TaskSettings settings = task.settings();
        List<SyncInboxRecord> claimed = repository.claimForApply(
                settings.name(), settings.apply().batchSize(), Instant.now(), owner);
        for (SyncInboxRecord record : claimed) {
            Instant started = Instant.now();
            applyOne(task, record);
            metrics.recordApplyDuration(settings.name(), Duration.between(started, Instant.now()));
        }
        if (!claimed.isEmpty()) {
            log.debug("Task '{}' applied {} staged record(s)", settings.name(), claimed.size());
        }
        return claimed.size();
    }

    /**
     * Captures the task's wildcards so {@link RecordApplier#apply} is called fully typed.
     *
     * <p>This one-line indirection is the whole cost of keeping the engine generic: the registry
     * necessarily holds {@code RegisteredTask<?, ?, ?>}, and a generic method is how Java turns those
     * wildcards back into type variables without a cast.
     */
    private <I, K, O> void applyOne(RegisteredTask<I, K, O> task, SyncInboxRecord record) {
        applier.apply(task, record.getId());
    }
}
