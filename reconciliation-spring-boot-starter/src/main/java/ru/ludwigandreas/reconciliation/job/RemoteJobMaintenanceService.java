package ru.ludwigandreas.reconciliation.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.ActiveJob;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.config.AmbiguousSubmitPolicy;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The two things that have to happen to jobs nobody is actively driving: expiring the ones that
 * outlived their usefulness, and deciding what a submit whose outcome is unknown actually was.
 *
 * <h2>Expiry</h2>
 *
 * <p>A job past its {@code max-lifetime} is cancelled at the partner where that is possible, marked
 * {@code EXPIRED}, and has its quota slot released. Without a hard stop, a job the partner has quietly
 * abandoned holds a slot forever while its lease is renewed perfectly - a leak that heartbeating
 * cannot detect, because the heartbeat is working.
 *
 * <h2>Ambiguous submits</h2>
 *
 * <p>A row still in {@code PENDING_SUBMIT} past its grace period means the request may or may not have
 * reached the partner. The resolution order is:
 *
 * <ol>
 *   <li><b>Ask.</b> If the fetcher implements {@code listActive()}, look for a job carrying this row's
 *       idempotency key and adopt its handle. This answers the question instead of guessing at it, and
 *       is why the key is committed before the call rather than after.</li>
 *   <li><b>Apply the policy.</b> {@code assume-submitted} marks the row {@code ORPHANED} and holds the
 *       slot until {@code max-lifetime} - correct for anything billable, where a duplicate costs real
 *       money. {@code resubmit} settles the row so the next submit pass starts a fresh job - correct
 *       only when a submission is cheap and genuinely idempotent on the partner's side.</li>
 * </ol>
 *
 * <p>There is no safe default across partners, which is why the setting is required and the context
 * refuses to start without it.
 */
public class RemoteJobMaintenanceService {

    /** Bounded page: one sweep must not try to settle an unbounded backlog in a single tick. */
    private static final int SWEEP_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(RemoteJobMaintenanceService.class);

    private final SyncRemoteJobRepository jobs;
    private final RemoteJobSettlement settlement;
    private final ReconciliationMetrics metrics;
    private final TransactionTemplate requiresNew;

    /**
     * Creates the service.
     *
     * @param jobs               the job table
     * @param settlement         terminal-state handling
     * @param metrics            instrumentation
     * @param transactionManager the transaction manager each commit runs against
     */
    public RemoteJobMaintenanceService(SyncRemoteJobRepository jobs,
                                       RemoteJobSettlement settlement,
                                       ReconciliationMetrics metrics,
                                       PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.settlement = settlement;
        this.metrics = metrics;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs one maintenance pass for a task.
     *
     * @param task the task
     * @return how many jobs were acted on
     */
    public int maintain(RegisteredTask<?, ?, ?> task) {
        return expire(task) + resolveAmbiguousSubmits(task);
    }

    private <I, K, O> int expire(RegisteredTask<I, K, O> task) {
        List<SyncRemoteJob> expired = jobs.findByStateInAndExpiresAtLessThan(
                RemoteJobState.uncovered(), Instant.now(), PageRequest.of(0, SWEEP_LIMIT));
        for (SyncRemoteJob job : expired) {
            settlement.cancelAndSettle(task, job, RemoteJobState.EXPIRED,
                    "Outlived the task's job.max-lifetime");
        }
        return expired.size();
    }

    private <I, K, O> int resolveAmbiguousSubmits(RegisteredTask<I, K, O> task) {
        TaskSettings settings = task.settings();
        TaskSettings.JobSettings jobSettings = settings.jobOrEmpty().orElseThrow();
        Instant cutoff = Instant.now().minus(jobSettings.submitGracePeriod());
        List<SyncRemoteJob> ambiguous = jobs.findByStateAndCreatedAtLessThan(
                RemoteJobState.PENDING_SUBMIT, cutoff, PageRequest.of(0, SWEEP_LIMIT));

        for (SyncRemoteJob job : ambiguous) {
            resolveOne(task, job, settings, jobSettings);
        }
        return ambiguous.size();
    }

    private <I, K, O> void resolveOne(RegisteredTask<I, K, O> task,
                                      SyncRemoteJob job,
                                      TaskSettings settings,
                                      TaskSettings.JobSettings jobSettings) {
        Optional<Object> adopted = findAtPartner(task, job);
        if (adopted.isPresent()) {
            adopt(task, job, adopted.get());
            metrics.recordAmbiguousSubmit(settings.name(), "adopted");
            log.info("Task '{}' adopted job {} from the partner's active listing; it was submitted "
                    + "after all", settings.name(), job.getId());
            return;
        }

        if (jobSettings.onAmbiguousSubmit() == AmbiguousSubmitPolicy.RESUBMIT) {
            settlement.settle(task, job.getId(), RemoteJobState.FAILED,
                    "The submit could not be confirmed and this task's policy is 'resubmit'");
            metrics.recordAmbiguousSubmit(settings.name(), "resubmitted");
            return;
        }

        requiresNew.executeWithoutResult(status -> {
            SyncRemoteJob stored = jobs.getByIdOrThrow(job.getId());
            stored.setState(RemoteJobState.ORPHANED);
            stored.setLastError("The submit could not be confirmed; assuming it reached the partner. "
                    + "The quota slot stays held until job.max-lifetime and nothing is resubmitted.");
            jobs.save(stored);
        });
        metrics.recordAmbiguousSubmit(settings.name(), "orphaned");
        log.warn("Task '{}' orphaned job {}: the submit could not be confirmed, the partner offers no "
                + "way to ask, and this task's policy is assume-submitted. Its quota slot stays held "
                + "until {}.", settings.name(), job.getId(), job.getExpiresAt());
    }

    /**
     * Looks for this row's idempotency key among the jobs the partner says are active.
     *
     * <p>Matching on the key, never on position or timing: two instances submitting concurrently
     * produce two active jobs, and adopting the wrong one attaches this row to somebody else's work
     * and leaves the real one unowned.
     */
    private <I, K, O> Optional<Object> findAtPartner(RegisteredTask<I, K, O> task, SyncRemoteJob job) {
        if (!(task.task().fetcher() instanceof Fetcher.JobFetcher)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Fetcher.JobFetcher<K, Object, O> fetcher = (Fetcher.JobFetcher<K, Object, O>) task.task().fetcher();
        try {
            return fetcher.listActive()
                    .flatMap(active -> active.stream()
                            .filter(candidate -> candidate.idempotencyKey().value().equals(job.getIdempotencyKey()))
                            .map(ActiveJob::handle)
                            .findFirst());
        } catch (Exception e) {
            // Failing to ask is not an answer. Fall through to the policy, which is what exists for
            // the case where the question cannot be put at all.
            log.warn("Task '{}' could not list the partner's active jobs while resolving job {}",
                    task.name(), job.getId(), e);
            return Optional.empty();
        }
    }

    private <I, K, O> void adopt(RegisteredTask<I, K, O> task, SyncRemoteJob job, Object handle) {
        @SuppressWarnings("unchecked")
        Fetcher.JobFetcher<K, Object, O> fetcher = (Fetcher.JobFetcher<K, Object, O>) task.task().fetcher();
        requiresNew.executeWithoutResult(status -> {
            SyncRemoteJob stored = jobs.getByIdOrThrow(job.getId());
            stored.setExternalHandle(fetcher.handleCodec().encode(handle));
            stored.setState(RemoteJobState.SUBMITTED);
            stored.setSubmittedAt(stored.getSubmittedAt() == null ? Instant.now() : stored.getSubmittedAt());
            stored.setNextPollAt(Instant.now());
            stored.setLastError(null);
            jobs.save(stored);
        });
    }
}
