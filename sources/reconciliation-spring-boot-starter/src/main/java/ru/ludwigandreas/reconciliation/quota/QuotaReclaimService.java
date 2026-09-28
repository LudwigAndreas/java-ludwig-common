package ru.ludwigandreas.reconciliation.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.JobStatus;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.config.QuotaReclaimPolicy;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.entity.QuotaLease;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.QuotaWaiterRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Takes back quota slots whose holder stopped heartbeating - carefully.
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * <p><b>An expired lease does not mean the remote work stopped.</b> The instance that held it may be
 * gone while the job it submitted is still running on the partner's side, still occupying the capacity
 * the quota is counting. Reclaiming the slot on expiry alone is how a partner's stated limit gets
 * quietly exceeded, and the excess is invisible from here because nothing in this system knows the
 * job is still running.
 *
 * <p>So the default policy, {@code verify-remote}, asks first: poll the handle the lease was held for,
 * and reclaim only if the job has reached a terminal state or has outlived its lifetime - cancelling
 * it at the partner where the fetcher supports that. {@code on-expiry} skips the check, and is only
 * appropriate for a partner with no status endpoint, where there is nothing to ask.
 *
 * <p>Every reclaim increments {@code reconciliation.quota.lease.reclaimed}. <b>Alert on any sustained
 * non-zero value.</b> It is the leading indicator of both quota leaks and duplicate remote work, and
 * neither shows up anywhere else until the integration has already stopped.
 */
public class QuotaReclaimService {

    /** Bounded page: one sweep must not try to verify an unbounded number of leases in a tick. */
    private static final int SWEEP_LIMIT = 50;

    /** How many heartbeat intervals a queued waiter may miss before its entry is swept. */
    private static final int WAITER_LIVENESS_INTERVALS = 3;

    private static final Logger log = LoggerFactory.getLogger(QuotaReclaimService.class);

    private final Map<String, ReconciliationProperties.Quota> configured;
    private final QuotaLeaseRepository leases;
    private final QuotaWaiterRepository waiters;
    private final SyncRemoteJobRepository jobs;
    private final TaskRegistry tasks;
    private final ReconciliationMetrics metrics;
    private final AuditSink auditLogger;
    private final TransactionTemplate requiresNew;

    /**
     * Creates the service.
     *
     * @param configured         the configured quotas, by name
     * @param leases             the lease table
     * @param waiters            the queue table
     * @param jobs               the job table, for finding what a lease was held for
     * @param tasks              the task registry, for reaching the fetcher that can ask the partner
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param transactionManager the transaction manager each commit runs against
     */
    public QuotaReclaimService(Map<String, ReconciliationProperties.Quota> configured,
                               QuotaLeaseRepository leases,
                               QuotaWaiterRepository waiters,
                               SyncRemoteJobRepository jobs,
                               TaskRegistry tasks,
                               ReconciliationMetrics metrics,
                               AuditSink auditLogger,
                               PlatformTransactionManager transactionManager) {
        this.configured = Map.copyOf(configured);
        this.leases = leases;
        this.waiters = waiters;
        this.jobs = jobs;
        this.tasks = tasks;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs one reclaim sweep.
     *
     * @return how many slots were taken back
     */
    public int sweep() {
        sweepStaleWaiters();
        List<QuotaLease> expired = leases.findByExpiresAtLessThanOrderByExpiresAt(
                Instant.now(), PageRequest.of(0, SWEEP_LIMIT));
        int reclaimed = 0;
        for (QuotaLease lease : expired) {
            if (reclaim(lease)) {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    /**
     * Removes queue entries whose instance stopped heartbeating.
     *
     * <p>Under FIFO a dead waiter at the head of the queue stops the quota granting anything at all,
     * so the liveness rule that applies to leases has to apply to the queue as well.
     */
    private void sweepStaleWaiters() {
        Duration longestHeartbeat = configured.values().stream()
                .map(ReconciliationProperties.Quota::getHeartbeatInterval)
                .max(Duration::compareTo)
                .orElse(Duration.ofMinutes(2));
        int removed = waiters.deleteStale(
                Instant.now().minus(longestHeartbeat.multipliedBy(WAITER_LIVENESS_INTERVALS)));
        if (removed > 0) {
            log.debug("Removed {} stale quota queue entr(ies)", removed);
        }
    }

    private boolean reclaim(QuotaLease lease) {
        ReconciliationProperties.Quota settings = configured.get(lease.getQuotaName());
        if (settings == null) {
            // The quota was removed from configuration while a lease was outstanding. Nothing counts
            // it any more, so holding the row achieves nothing.
            return release(lease, "quota no longer configured", "removed");
        }
        boolean pastLifetime = lease.getMaxLifetimeAt().isBefore(Instant.now());
        if (settings.getReclaim() == QuotaReclaimPolicy.ON_EXPIRY || pastLifetime) {
            return release(lease, pastLifetime ? "past max-lifetime" : "expired without verification",
                    settings.getReclaim().name().toLowerCase(Locale.ROOT));
        }
        return verifyThenRelease(lease, settings);
    }

    /**
     * Asks the partner whether the work this slot was held for is really over.
     *
     * <p>A lease with no job attached is a synchronous pass's slot: nothing outlives the process that
     * held it, so an expired one is safe to take back. A lease whose job cannot be reached is left
     * alone until {@code max-lifetime} - being unable to ask is not evidence that the answer is "it
     * finished".
     */
    private boolean verifyThenRelease(QuotaLease lease, ReconciliationProperties.Quota settings) {
        Optional<SyncRemoteJob> job = leaseJob(lease);
        if (job.isEmpty()) {
            return release(lease, "no remote job was attached to this slot", "verify-remote");
        }
        SyncRemoteJob remoteJob = job.get();
        if (remoteJob.getState().isTerminal()) {
            return release(lease, "its job is already " + remoteJob.getState(), "verify-remote");
        }

        Optional<JobStatus> status = probe(remoteJob);
        if (status.isEmpty()) {
            log.warn("Quota '{}' could not verify lease {} against the partner; holding the slot until "
                            + "max-lifetime at {} rather than risk exceeding the partner's limit",
                    lease.getQuotaName(), lease.getId(), lease.getMaxLifetimeAt());
            return false;
        }
        if (status.get() instanceof JobStatus.Running) {
            log.info("Quota '{}' is holding lease {}: its holder is gone but job {} is still running "
                    + "at the partner", lease.getQuotaName(), lease.getId(), remoteJob.getId());
            return false;
        }
        cancelRemote(remoteJob);
        return release(lease, "its job is no longer running at the partner", "verify-remote");
    }

    private Optional<SyncRemoteJob> leaseJob(QuotaLease lease) {
        return lease.getJobId() == null ? Optional.empty() : jobs.findById(lease.getJobId());
    }

    private Optional<JobStatus> probe(SyncRemoteJob job) {
        return jobFetcher(job).flatMap(fetcher -> {
            if (job.getExternalHandle() == null) {
                // PENDING_SUBMIT with no handle: there is nothing to ask about, and the ambiguity
                // resolver - not this sweep - is what decides what that means.
                return Optional.empty();
            }
            try {
                return Optional.of(fetcher.poll(fetcher.handleCodec().decode(job.getExternalHandle())));
            } catch (Exception e) {
                log.debug("Could not probe job {} while verifying a lease", job.getId(), e);
                return Optional.empty();
            }
        });
    }

    private void cancelRemote(SyncRemoteJob job) {
        jobFetcher(job).ifPresent(fetcher -> {
            try {
                fetcher.cancel(fetcher.handleCodec().decode(job.getExternalHandle()));
            } catch (Exception e) {
                log.debug("Could not cancel job {} while reclaiming its slot", job.getId(), e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<Fetcher.JobFetcher<Object, Object, Object>> jobFetcher(SyncRemoteJob job) {
        return tasks.find(job.getTaskName())
                .map(RegisteredTask::task)
                .map(task -> task.fetcher())
                .filter(Fetcher.JobFetcher.class::isInstance)
                .map(fetcher -> (Fetcher.JobFetcher<Object, Object, Object>) fetcher);
    }

    private boolean release(QuotaLease lease, String reason, String policy) {
        requiresNew.executeWithoutResult(status -> leases.deleteById(lease.getId()));
        metrics.recordLeaseReclaimed(lease.getQuotaName(), policy);
        auditLogger.record(ReconciliationAuditEvent.builder(lease.getTaskName(), Category.LEASE,
                        "lease.reclaimed")
                .subject(lease.getQuotaName())
                .detail("held by " + lease.getOwnerInstance() + "; " + reason)
                .build());
        log.warn("Reclaimed a slot of quota '{}' from {} ({}). A sustained non-zero reclaim rate means "
                        + "leases are leaking or remote work is being duplicated.",
                lease.getQuotaName(), lease.getOwnerInstance(), reason);
        return true;
    }
}
