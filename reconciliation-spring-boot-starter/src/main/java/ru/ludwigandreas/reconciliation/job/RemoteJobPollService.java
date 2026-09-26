package ru.ludwigandreas.reconciliation.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.JobStatus;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.quota.QuotaRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The poll pass: asks the partner how its in-flight jobs are doing.
 *
 * <h2>Why polling has its own schedule and its own budget</h2>
 *
 * <p>Probing a hundred running jobs is cheap and should happen often; collecting one finished result
 * is expensive and happens rarely. Sharing a schedule means either the probes are throttled to the
 * collections' pace - so a job that finished two minutes ago is noticed in twenty - or the
 * collections inherit the probes' concurrency and a partner's result endpoint is hit by ten
 * simultaneous multi-megabyte downloads.
 *
 * <h2>Adaptive backoff</h2>
 *
 * <p>Each job's own probe interval grows while it keeps answering "still running". A job that has been
 * running for two hours is not about to finish in the next thirty seconds, and probing it at the same
 * rate as one submitted a minute ago spends the partner's rate budget on the least informative
 * question available. A partner's own {@code Retry-After} or completion estimate overrides the curve
 * entirely, because it knows and the curve is guessing.
 */
public class RemoteJobPollService {

    private static final Logger log = LoggerFactory.getLogger(RemoteJobPollService.class);

    private final SyncRemoteJobRepository jobs;
    private final QuotaRegistry quotas;
    private final RemoteJobSettlement settlement;
    private final AuditSink auditLogger;
    private final String owner;
    private final TransactionTemplate requiresNew;

    /**
     * Creates the service.
     *
     * @param jobs               the job table
     * @param quotas             partner-scoped concurrency budgets, renewed while a job runs
     * @param settlement         terminal-state handling, shared with the other job passes
     * @param auditLogger        the audit trail
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager each commit runs against
     */
    public RemoteJobPollService(SyncRemoteJobRepository jobs,
                                QuotaRegistry quotas,
                                RemoteJobSettlement settlement,
                                AuditSink auditLogger,
                                String owner,
                                PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.quotas = quotas;
        this.settlement = settlement;
        this.auditLogger = auditLogger;
        this.owner = owner;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs one poll pass.
     *
     * @param task the task
     * @return how many jobs were probed
     */
    public int pollPass(RegisteredTask<?, ?, ?> task) {
        return pollTyped(task);
    }

    private <I, K, O> int pollTyped(RegisteredTask<I, K, O> task) {
        TaskSettings settings = task.settings();
        TaskSettings.JobSettings jobSettings = settings.jobOrEmpty().orElseThrow();
        if (!(task.task().fetcher() instanceof Fetcher.JobFetcher)) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        Fetcher.JobFetcher<K, Object, O> fetcher = (Fetcher.JobFetcher<K, Object, O>) task.task().fetcher();

        List<SyncRemoteJob> claimed = jobs.claimForPoll(settings.name(),
                jobSettings.maxConcurrentSubmits(), Instant.now(), owner);
        for (SyncRemoteJob job : claimed) {
            probe(task, fetcher, job, settings, jobSettings);
        }
        return claimed.size();
    }

    private <I, K, O> void probe(RegisteredTask<I, K, O> task,
                                 Fetcher.JobFetcher<K, Object, O> fetcher,
                                 SyncRemoteJob job,
                                 TaskSettings settings,
                                 TaskSettings.JobSettings jobSettings) {
        // Renewing before the probe, not after: the probe is a network call that may take as long as
        // the partner feels like, and a lease that expires during it is a slot another instance can
        // take while this job is demonstrably still running.
        quotas.adopt(job.getQuotaLeaseId()).ifPresent(lease -> {
            if (!lease.renew()) {
                log.warn("Task '{}' lost the quota lease for job {} while it was still running",
                        settings.name(), job.getId());
            }
        });

        JobStatus status;
        try {
            status = fetcher.poll(fetcher.handleCodec().decode(job.getExternalHandle()));
        } catch (Exception e) {
            // A failed probe says nothing about the job. Back off and ask again; only the partner's
            // own answer, or the lifetime running out, settles a job.
            log.debug("Task '{}' could not probe job {}", settings.name(), job.getId(), e);
            reschedule(job, jobSettings, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        apply(task, job, status, settings, jobSettings);
    }

    private <I, K, O> void apply(RegisteredTask<I, K, O> task,
                                 SyncRemoteJob job,
                                 JobStatus status,
                                 TaskSettings settings,
                                 TaskSettings.JobSettings jobSettings) {
        if (status instanceof JobStatus.Running running) {
            transition(job, RemoteJobState.RUNNING, settings);
            reschedule(job, jobSettings,
                    jobSettings.honourRetryAfter() ? running.retryAfter() : null, null);
            return;
        }
        if (status instanceof JobStatus.Succeeded) {
            requiresNew.executeWithoutResult(ignored -> {
                SyncRemoteJob stored = jobs.getByIdOrThrow(job.getId());
                stored.setState(RemoteJobState.SUCCEEDED);
                stored.setLastPolledAt(Instant.now());
                jobs.save(stored);
            });
            audit(settings, job, "job.succeeded", null);
            return;
        }
        if (status instanceof JobStatus.Failed failed) {
            settlement.settle(task, job.getId(), RemoteJobState.FAILED, failed.reason());
            return;
        }
        settlement.settle(task, job.getId(), RemoteJobState.EXPIRED,
                "The partner no longer knows this job");
    }

    private void transition(SyncRemoteJob job, RemoteJobState state, TaskSettings settings) {
        if (job.getState() != state) {
            audit(settings, job, "job." + state.name().toLowerCase(Locale.ROOT), null);
        }
    }

    /**
     * Sets the job's next probe time.
     *
     * <p>The interval is per job and grows with that job's own probe count, capped at
     * {@code poll.max-interval}. The partner's guidance, when honoured, replaces it outright.
     */
    private void reschedule(SyncRemoteJob job,
                            TaskSettings.JobSettings jobSettings,
                            Duration partnerGuidance,
                            String error) {
        requiresNew.executeWithoutResult(ignored -> {
            SyncRemoteJob stored = jobs.getByIdOrThrow(job.getId());
            stored.setPollAttempts(stored.getPollAttempts() + 1);
            stored.setLastPolledAt(Instant.now());
            stored.setState(stored.getState() == RemoteJobState.SUBMITTED
                    ? RemoteJobState.RUNNING : stored.getState());
            if (error != null) {
                stored.setLastError(error);
            }
            Duration interval = partnerGuidance != null
                    ? partnerGuidance
                    : nextInterval(jobSettings, stored.getPollAttempts());
            stored.setNextPollAt(Instant.now().plus(interval));
            jobs.save(stored);
        });
    }

    private static Duration nextInterval(TaskSettings.JobSettings jobSettings, int attempts) {
        Duration base = jobSettings.pollSchedule().fixedDelay();
        double grown = base.toMillis() * Math.pow(jobSettings.pollBackoffMultiplier(), Math.max(0, attempts - 1));
        Duration interval = Duration.ofMillis((long) Math.min(grown, jobSettings.pollMaxInterval().toMillis()));
        return interval.isZero() ? base : interval;
    }

    private void audit(TaskSettings settings, SyncRemoteJob job, String event, String detail) {
        if (!settings.audit().enabled()) {
            return;
        }
        auditLogger.record(ReconciliationAuditEvent.builder(settings.name(), Category.JOB, event)
                .subject(String.valueOf(job.getId())).detail(detail).build());
    }
}
