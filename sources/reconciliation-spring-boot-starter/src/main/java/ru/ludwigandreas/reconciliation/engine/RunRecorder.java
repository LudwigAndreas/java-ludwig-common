package ru.ludwigandreas.reconciliation.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.entity.RunKind;
import ru.ludwigandreas.reconciliation.entity.SyncRun;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.reconciliation.repository.SyncRunRepository;

/**
 * Writes the run-history row as a run moves through its life.
 *
 * <p>Every write is its own committed transaction, so the row is visible to every replica as it changes - a poll of a
 * manual run on another replica sees {@code RUNNING} while it runs - and so a failing pass cannot roll its own history
 * back. Every write is also best-effort: the row is telemetry, so a failure to write it is logged and metered and
 * never fails the reconciliation it describes.
 */
public class RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final SyncRunRepository runs;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final ReconciliationMetrics metrics;

    /**
     * Creates the recorder.
     *
     * @param runs               the run table
     * @param transactionManager the application's transaction manager
     * @param json               serializes a dry-run report or a backfill's counts
     * @param metrics            where a failed write is counted
     */
    public RunRecorder(SyncRunRepository runs, PlatformTransactionManager transactionManager, ObjectMapper json,
                       ReconciliationMetrics metrics) {
        this.runs = runs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * Records a run an operator asked for, which has its lease and has not started.
     *
     * @param id          the lease's run id
     * @param task        the task
     * @param tier        the tier
     * @param kind        manual, dry run or backfill
     * @param triggeredBy who asked
     * @param instance    the lease owner
     */
    public void submitted(UUID id, String task, DemandTier tier, RunKind kind, String triggeredBy, String instance) {
        write(task, () -> runs.save(newRun(id, task, tier, kind, triggeredBy, instance, Instant.now())));
    }

    /**
     * Records that a run started: a submitted run moves to running, a scheduled run is inserted as running.
     *
     * @param id            the lease's run id
     * @param task          the task
     * @param tier          the tier
     * @param kind          what started it
     * @param instance      the lease owner
     * @param correlationId the run's correlation id
     */
    public void started(UUID id, String task, DemandTier tier, RunKind kind, String instance, String correlationId) {
        write(task, () -> {
            Instant now = Instant.now();
            SyncRun run = runs.findById(id).orElseGet(() -> newRun(id, task, tier, kind, null, instance, now));
            run.setStartedAt(now);
            run.setCorrelationId(correlationId);
            runs.save(run);
        });
    }

    /**
     * Records how a run ended.
     *
     * @param id          the lease's run id
     * @param task        the task
     * @param outcome     how it ended
     * @param failureCode why it failed, or null
     * @param tally       what it counted
     * @param result      a dry run's report or a backfill's counts, or null
     */
    public void finished(UUID id, String task, RunOutcome outcome, String failureCode, RunTally tally,
                         Object result) {
        write(task, () -> runs.findById(id).ifPresent(run -> {
            run.setFinishedAt(Instant.now());
            run.setOutcome(outcome);
            run.setFailureCode(failureCode);
            run.setDemanded(tally.demanded());
            run.setFetched(tally.fetched());
            run.setProduced(tally.produced());
            run.setResultJson(serialize(result));
            runs.save(run);
        }));
    }

    /**
     * Removes the row of a run that never started, because the manual-run capacity refused it.
     *
     * @param id   the run id
     * @param task the task
     */
    public void discard(UUID id, String task) {
        write(task, () -> runs.deleteById(id));
    }

    private static SyncRun newRun(UUID id, String task, DemandTier tier, RunKind kind, String triggeredBy,
                                  String instance, Instant submittedAt) {
        SyncRun run = new SyncRun();
        run.setId(id);
        run.setTaskName(task);
        run.setTier(tier);
        run.setKind(kind);
        run.setTriggeredBy(triggeredBy);
        run.setInstance(instance);
        run.setSubmittedAt(submittedAt);
        return run;
    }

    private String serialize(Object result) {
        if (result == null) {
            return null;
        }
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A run result could not be serialized", e);
        }
    }

    private void write(String task, Runnable work) {
        try {
            transaction.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - run history is telemetry: whatever stopped the write, the reconciliation
            // it describes must carry on, and the failure must be visible rather than swallowed silently.
            metrics.recordRunRecordFailed(task);
            log.warn("Could not record run history for task '{}': {}", task, e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}
