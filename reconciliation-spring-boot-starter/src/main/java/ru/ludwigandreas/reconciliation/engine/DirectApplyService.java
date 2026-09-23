package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.api.ReconcileContext;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.NotFoundPolicy;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Applies fetch outcomes inline, without a staging row - {@code mode: direct}.
 *
 * <h2>What direct mode gives up, stated plainly</h2>
 *
 * <p>Everything the staging table exists to provide:
 *
 * <ul>
 *   <li><b>Retry without re-calling the partner.</b> A failure here is retried by refetching on the
 *       next run, because there is nothing written down to retry from.</li>
 *   <li><b>The engine's idempotency short-circuit and stale-write guard.</b> Both are answered from
 *       the history of applied records for a key, and in this mode there is no history. A reconciler
 *       used in direct mode must do its own ordering check, or out-of-order responses will regress
 *       local state silently.</li>
 *   <li><b>Per-record dead-lettering and operator requeue.</b> There is no row to quarantine and none
 *       to requeue.</li>
 * </ul>
 *
 * <p>What it keeps is per-record transaction isolation: one record failing still does not roll back
 * another's success. That is why this mode is usable at all, and why it is reasonable for an
 * integration whose data is cheap to refetch and not worth a row.
 */
public class DirectApplyService {

    private static final Logger log = LoggerFactory.getLogger(DirectApplyService.class);

    private final ReconciliationMetrics metrics;
    private final ReconciliationAuditLogger auditLogger;
    private final TransactionTemplate perRecord;

    /**
     * Creates the service.
     *
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param transactionManager the transaction manager each record's apply runs against
     */
    public DirectApplyService(ReconciliationMetrics metrics,
                              ReconciliationAuditLogger auditLogger,
                              PlatformTransactionManager transactionManager) {
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.perRecord = new TransactionTemplate(transactionManager);
        this.perRecord.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies a batch of outcomes, each in its own transaction.
     *
     * @param <I>      local record type
     * @param <K>      correlation key type
     * @param <O>      external record type
     * @param task     the task
     * @param outcomes what the fetch produced
     * @param context  the run
     * @return how many records were applied or considered
     */
    public <I, K, O> int apply(RegisteredTask<I, K, O> task,
                               List<FetchOutcome<K, O>> outcomes,
                               RunContext context) {
        int handled = 0;
        for (FetchOutcome<K, O> outcome : outcomes) {
            handled += applyOne(task, outcome, context) ? 1 : 0;
        }
        return handled;
    }

    private <I, K, O> boolean applyOne(RegisteredTask<I, K, O> task,
                                       FetchOutcome<K, O> outcome,
                                       RunContext context) {
        TaskSettings settings = task.settings();
        String key = task.task().keyCodec().encode(outcome.key());
        try {
            return Boolean.TRUE.equals(perRecord.execute(status -> {
                ReconcileResult result = reconcile(task, outcome, context);
                if (result == null) {
                    return false;
                }
                record(settings, key, result, context);
                return true;
            }));
        } catch (Exception e) {
            // Per-record isolation is the one guarantee direct mode keeps: this record failed, the
            // rest of the batch has not. There is nothing to write down, so the next run refetches.
            metrics.recordRecordOutcome(settings.name(), "failed");
            log.warn("Task '{}' failed to apply key {} directly; it will be refetched next run",
                    settings.name(), key, e);
            return false;
        }
    }

    private <I, K, O> ReconcileResult reconcile(RegisteredTask<I, K, O> task,
                                                FetchOutcome<K, O> outcome,
                                                RunContext context) {
        TaskSettings settings = task.settings();
        java.util.Optional<I> local = task.task().demand().byKey(outcome.key());
        if (local.isEmpty()) {
            return null;
        }
        ReconcileContext reconcileContext = new ReconcileContext(settings.name(), context.runId(), 1,
                stampOf(task, outcome), ExternalStamp.none(), Instant.now());

        if (outcome instanceof FetchOutcome.Found<K, O> hit) {
            return task.task().reconciler().reconcile(local.get(), hit.record(), reconcileContext);
        }
        if (outcome instanceof FetchOutcome.NotFound<K, O>) {
            metrics.recordNotFound(settings.name(), settings.notFound().name().toLowerCase(Locale.ROOT));
            if (settings.notFound() != NotFoundPolicy.MARK_MISSING) {
                return null;
            }
            return task.task().reconciler().reconcileMissing(local.get(), reconcileContext);
        }
        FetchOutcome.Failed<K, O> failed = (FetchOutcome.Failed<K, O>) outcome;
        metrics.recordRecordOutcome(settings.name(), "fetch_failed");
        log.debug("Task '{}' could not fetch key {}: {}", settings.name(),
                task.task().keyCodec().encode(outcome.key()), failed.reason());
        return null;
    }

    private <I, K, O> ExternalStamp stampOf(RegisteredTask<I, K, O> task, FetchOutcome<K, O> outcome) {
        return outcome instanceof FetchOutcome.Found<K, O> hit
                ? task.task().stampOf(hit.record())
                : ExternalStamp.none();
    }

    private void record(TaskSettings settings, String key, ReconcileResult result, RunContext context) {
        // An if-chain rather than a pattern switch: this module targets Java 17, where switching over
        // sealed type patterns is still a preview feature.
        String outcome;
        if (result instanceof ReconcileResult.Applied) {
            outcome = "applied";
        } else if (result instanceof ReconcileResult.Unchanged) {
            outcome = "unchanged";
        } else if (result instanceof ReconcileResult.Rejected) {
            outcome = "rejected";
        } else {
            outcome = "deferred";
        }
        metrics.recordRecordOutcome(settings.name(), outcome);
        if (settings.audit().enabled()) {
            auditLogger.record(AuditEvent.builder(settings.name(), AuditEvent.Category.RECORD,
                            "record." + outcome)
                    .subject(key).runId(context.runId()).correlationId(context.correlationId()).build());
        }
    }
}
