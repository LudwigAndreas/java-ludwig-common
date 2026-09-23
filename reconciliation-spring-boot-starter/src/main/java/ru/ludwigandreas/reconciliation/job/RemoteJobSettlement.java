package ru.ludwigandreas.reconciliation.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.quota.QuotaRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Brings a remote job to a terminal state, releases what it was holding, and lets its demand come
 * back.
 *
 * <p>Shared by the poll pass, the collect pass and the expiry sweep, because getting this sequence
 * wrong in one of them and right in the others is how a quota slowly leaks: the release has to happen
 * on every terminal path, including the ones that are rare enough never to be exercised in testing.
 *
 * <h2>What "requeue the demand" means here</h2>
 *
 * <p>Nothing explicit, and that is deliberate. A job's keys are excluded from the submit pass only
 * while the job is non-terminal, so settling it is what makes them eligible again - the demand query
 * will offer them on the next pass if the local records still need external state, and will not if
 * they do not. An explicit requeue would be a second source of truth about what still needs syncing,
 * and it would be the stale one.
 *
 * <p>Records already staged from a partially collected job are a different matter: those are rows,
 * and they keep their own lifecycle.
 */
public class RemoteJobSettlement {

    private static final Logger log = LoggerFactory.getLogger(RemoteJobSettlement.class);

    private final SyncRemoteJobRepository jobs;
    private final QuotaRegistry quotas;
    private final ReconciliationMetrics metrics;
    private final ReconciliationAuditLogger auditLogger;
    private final TransactionTemplate requiresNew;

    /**
     * Creates the settlement.
     *
     * @param jobs               the job table
     * @param quotas             partner-scoped concurrency budgets
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param transactionManager the transaction manager each commit runs against
     */
    public RemoteJobSettlement(SyncRemoteJobRepository jobs,
                               QuotaRegistry quotas,
                               ReconciliationMetrics metrics,
                               ReconciliationAuditLogger auditLogger,
                               PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.quotas = quotas;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Settles a job.
     *
     * @param <I>    local record type
     * @param <K>    correlation key type
     * @param <O>    external record type
     * @param task   the task
     * @param jobId  the job
     * @param state  the terminal state to settle into
     * @param reason what to record about it
     */
    public <I, K, O> void settle(RegisteredTask<I, K, O> task,
                                 java.util.UUID jobId,
                                 RemoteJobState state,
                                 String reason) {
        TaskSettings settings = task.settings();
        SyncRemoteJob settled = requiresNew.execute(status -> {
            SyncRemoteJob stored = jobs.getByIdOrThrow(jobId);
            stored.setState(state);
            stored.setSettledAt(Instant.now());
            stored.setLastError(reason);
            return jobs.save(stored);
        });

        // Released after the state is committed, never before: a slot given back while the row still
        // says the job is running lets the submit pass start a replacement for work the partner has
        // not been told to stop.
        releaseLease(settled, settings);

        Duration lifetime = Duration.between(settled.getCreatedAt(), Instant.now());
        metrics.recordJobSettled(settings.name(), state.name().toLowerCase(Locale.ROOT),
                lifetime, settled.getPollAttempts());
        if (settings.audit().enabled()) {
            auditLogger.record(AuditEvent.builder(settings.name(), AuditEvent.Category.JOB,
                            "job." + state.name().toLowerCase(Locale.ROOT))
                    .subject(String.valueOf(jobId)).detail(reason).build());
        }
        log.info("Task '{}' settled job {} as {} after {} ({} poll(s)): {}",
                settings.name(), jobId, state, lifetime, settled.getPollAttempts(), reason);
    }

    /**
     * Cancels a job at the partner, where the fetcher supports it, and then settles it.
     *
     * <p>Cancelling before settling, so that a partner which does support cancellation is told to stop
     * before this system stops counting the work. The other order releases the slot while the job is
     * still running and still costing money.
     *
     * @param <I>     local record type
     * @param <K>     correlation key type
     * @param <O>     external record type
     * @param task    the task
     * @param job     the job
     * @param state   the terminal state to settle into
     * @param reason  what to record about it
     */
    public <I, K, O> void cancelAndSettle(RegisteredTask<I, K, O> task,
                                          SyncRemoteJob job,
                                          RemoteJobState state,
                                          String reason) {
        if (task.task().fetcher() instanceof Fetcher.JobFetcher && job.getExternalHandle() != null) {
            @SuppressWarnings("unchecked")
            Fetcher.JobFetcher<K, Object, O> fetcher =
                    (Fetcher.JobFetcher<K, Object, O>) task.task().fetcher();
            try {
                fetcher.cancel(fetcher.handleCodec().decode(job.getExternalHandle()));
            } catch (Exception e) {
                // A partner that cannot be told to stop is a reason to record the fact, not a reason
                // to keep holding a slot for a job this system has given up on.
                log.warn("Task '{}' could not cancel job {} at the partner; settling it anyway",
                        task.name(), job.getId(), e);
            }
        }
        settle(task, job.getId(), state, reason);
    }

    private void releaseLease(SyncRemoteJob job, TaskSettings settings) {
        if (job.getQuotaLeaseId() == null) {
            return;
        }
        quotas.adopt(job.getQuotaLeaseId()).ifPresent(lease -> {
            lease.close();
            if (settings.audit().enabled()) {
                auditLogger.record(AuditEvent.builder(settings.name(), AuditEvent.Category.LEASE,
                                "lease.released")
                        .subject(settings.quotaName().orElse(null))
                        .detail("job " + job.getId() + " settled as " + job.getState())
                        .build());
            }
        });
    }
}
