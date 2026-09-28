package ru.ludwigandreas.reconciliation.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.IdempotencyKey;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.quota.QuotaLeaseHandle;
import ru.ludwigandreas.reconciliation.quota.QuotaRegistry;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The submit pass: turns demand into remote jobs, gated by the partner's quota.
 *
 * <h2>The write order, and why it cannot be reordered</h2>
 *
 * <ol>
 *   <li><b>Insert the job row as {@code PENDING_SUBMIT} with a fresh idempotency key, take the quota
 *       slot, and commit.</b></li>
 *   <li><b>Submit</b>, passing the idempotency key.</li>
 *   <li><b>Update to {@code SUBMITTED} with the handle, and commit.</b></li>
 * </ol>
 *
 * <p>The hazard the order exists for: if the request succeeds and this instance dies before step 3, a
 * naive restart would resubmit - burning a quota slot and possibly a billable remote job that is
 * already running. Step 1 leaves evidence that a job <em>may</em> exist, which turns "we have no idea"
 * into "this is ambiguous, and here is the key to ask about". Committing the row after the call, or
 * taking the slot after the call, both lose that.
 *
 * <p>Every deviation from this order is silent in testing and expensive in production, which is why
 * it is written down here rather than left to be inferred from the code.
 */
public class RemoteJobSubmitService {

    private static final Logger log = LoggerFactory.getLogger(RemoteJobSubmitService.class);

    private final SyncRemoteJobRepository jobs;
    private final SyncInboxRecordRepository records;
    private final QuotaRegistry quotas;
    private final RateLimitRegistry rateLimits;
    private final AuditSink auditLogger;
    private final DemandKeys demandKeys;
    private final String owner;
    private final TransactionTemplate requiresNew;

    /**
     * Creates the service.
     *
     * @param jobs               the job table
     * @param records            the staging table, for the suppression filter
     * @param quotas             partner-scoped concurrency budgets
     * @param rateLimits         partner-scoped request-rate budgets
     * @param auditLogger        the audit trail
     * @param demandKeys         the demand-keys codec
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager each commit runs against
     */
    public RemoteJobSubmitService(SyncRemoteJobRepository jobs,
                                  SyncInboxRecordRepository records,
                                  QuotaRegistry quotas,
                                  RateLimitRegistry rateLimits,
                                  AuditSink auditLogger,
                                  DemandKeys demandKeys,
                                  String owner,
                                  PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.records = records;
        this.quotas = quotas;
        this.rateLimits = rateLimits;
        this.auditLogger = auditLogger;
        this.demandKeys = demandKeys;
        this.owner = owner;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs one submit pass.
     *
     * @param task the task
     * @return how many jobs were started
     */
    public int submitPass(RegisteredTask<?, ?, ?> task) {
        return submitTyped(task);
    }

    private <I, K, O> int submitTyped(RegisteredTask<I, K, O> task) {
        TaskSettings settings = task.settings();
        TaskSettings.JobSettings job = settings.jobOrEmpty().orElseThrow();
        if (!(task.task().fetcher() instanceof Fetcher.JobFetcher)) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        Fetcher.JobFetcher<K, Object, O> fetcher = (Fetcher.JobFetcher<K, Object, O>) task.task().fetcher();

        List<List<String>> chunks = pendingChunks(task, settings);
        int started = 0;
        for (List<String> chunk : chunks) {
            if (started >= job.maxConcurrentSubmits()) {
                break;
            }
            if (!submitOne(task, fetcher, chunk, settings, job)) {
                // No slot, or the partner refused. Either way there is nothing to gain from trying
                // the next chunk in the same pass against the same saturated resource.
                break;
            }
            started++;
        }
        return started;
    }

    /**
     * Demand that is not already covered by a job, chunked for submission.
     *
     * <p>Excluding the keys of every non-terminal job is what "mark demand in-flight" means here: the
     * job row is the marker, and it already has to exist for other reasons. A separate flag on the
     * local record would be a second source of truth that can disagree with the first.
     */
    private <I, K, O> List<List<String>> pendingChunks(RegisteredTask<I, K, O> task, TaskSettings settings) {
        Set<String> covered = new HashSet<>();
        jobs.findByTaskNameAndStateIn(settings.name(), RemoteJobState.uncovered())
                .forEach(job -> covered.addAll(demandKeys.read(job.getDemandKeys())));
        covered.addAll(records.findSuppressedKeys(settings.name(), Instant.now()));

        DemandRequest request = new DemandRequest(settings.name(), DemandTier.HOT, null,
                settings.demand().maxRecordsPerRun(), java.util.UUID.randomUUID());
        Set<String> keys = new LinkedHashSet<>();
        for (I local : task.task().demand().demand(request)) {
            String key = task.encodedKeyOf(local);
            if (!covered.contains(key)) {
                keys.add(key);
            }
        }
        return chunk(List.copyOf(keys), settings.fetch().submitBatchSize());
    }

    private <I, K, O> boolean submitOne(RegisteredTask<I, K, O> task,
                                        Fetcher.JobFetcher<K, Object, O> fetcher,
                                        List<String> chunk,
                                        TaskSettings settings,
                                        TaskSettings.JobSettings job) {
        Optional<QuotaLeaseHandle> lease = quotas.tryAcquire(settings, null);
        if (settings.quotaName().isPresent() && lease.isEmpty()) {
            log.debug("Task '{}' has no free slot on quota '{}'; deferring {} key(s) to the next pass",
                    settings.name(), settings.quotaName().orElseThrow(), chunk.size());
            return false;
        }

        // STEP 1: the row and the slot exist before the call does.
        IdempotencyKey idempotencyKey = IdempotencyKey.random();
        SyncRemoteJob row = requiresNew.execute(status -> {
            SyncRemoteJob created = new SyncRemoteJob();
            created.setTaskName(settings.name());
            created.setIdempotencyKey(idempotencyKey.value());
            created.setState(RemoteJobState.PENDING_SUBMIT);
            created.setDemandKeys(demandKeys.write(chunk));
            created.setExpiresAt(Instant.now().plus(job.maxLifetime()));
            created.setOwnerInstance(owner);
            created.setCreatedAt(Instant.now());
            created.setQuotaLeaseId(lease.map(QuotaLeaseHandle::leaseId).orElse(null));
            return jobs.save(created);
        });
        audit(settings, "job.pending_submit", row, null);

        try {
            rateLimits.acquire(settings);
            // STEP 2: the call.
            List<K> typedKeys = chunk.stream().map(task.task().keyCodec()::decode).toList();
            Object handle = fetcher.submit(typedKeys, idempotencyKey);

            // STEP 3: record what came back.
            requiresNew.executeWithoutResult(status -> {
                SyncRemoteJob stored = jobs.getByIdOrThrow(row.getId());
                stored.setExternalHandle(fetcher.handleCodec().encode(handle));
                stored.setState(RemoteJobState.SUBMITTED);
                stored.setSubmittedAt(Instant.now());
                stored.setNextPollAt(Instant.now().plus(job.pollSchedule().fixedDelay()));
                jobs.save(stored);
            });
            audit(settings, "job.submitted", row, null);
            return true;
        } catch (Exception e) {
            // The row stays PENDING_SUBMIT on purpose. Whether the partner accepted the request is
            // exactly what is unknown, and guessing here - by failing the row, or by releasing the
            // slot - is what the ambiguity resolver exists to avoid doing blindly.
            log.warn("Task '{}' could not complete a submit; job {} stays PENDING_SUBMIT and will be "
                    + "resolved by the ambiguity policy", settings.name(), row.getId(), e);
            requiresNew.executeWithoutResult(status -> {
                SyncRemoteJob stored = jobs.getByIdOrThrow(row.getId());
                stored.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                jobs.save(stored);
            });
            return false;
        }
    }

    private void audit(TaskSettings settings, String event, SyncRemoteJob job, String detail) {
        if (!settings.audit().enabled()) {
            return;
        }
        auditLogger.record(ReconciliationAuditEvent.builder(settings.name(), Category.JOB, event)
                .subject(String.valueOf(job.getId()))
                .transition(null, job.getState())
                .detail(detail)
                .build());
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>((items.size() / size) + 1);
        for (int start = 0; start < items.size(); start += size) {
            chunks.add(List.copyOf(items.subList(start, Math.min(items.size(), start + size))));
        }
        return chunks;
    }

}
