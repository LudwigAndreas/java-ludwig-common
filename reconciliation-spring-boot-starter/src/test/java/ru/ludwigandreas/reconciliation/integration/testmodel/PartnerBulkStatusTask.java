package ru.ludwigandreas.reconciliation.integration.testmodel;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.reconciliation.api.ActiveJob;
import ru.ludwigandreas.reconciliation.api.DemandProvider;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.IdempotencyKey;
import ru.ludwigandreas.reconciliation.api.JobStatus;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.api.Reconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationTask;
import ru.ludwigandreas.reconciliation.api.SyncTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An asynchronous-job task, and the in-process partner that answers it.
 *
 * <p>The partner is programmable in exactly the ways the dangerous paths need: it can accept a submit
 * and then pretend the caller never heard the answer, it can report a job as running forever, it can
 * forget a job entirely, and it can list its active jobs - or refuse to, which is what forces the
 * ambiguity policy to decide.
 */
@ReconciliationTask("partner-bulk-status")
public class PartnerBulkStatusTask implements SyncTask<LocalOrder, String, BillingRecord> {

    private final LocalOrderRepository orders;
    private final StubPartner partner;

    private final Map<String, Job> remoteJobs = new LinkedHashMap<>();
    private final AtomicInteger handles = new AtomicInteger();
    private final List<String> cancelled = new ArrayList<>();

    private volatile boolean swallowSubmitResponse;
    private volatile boolean supportsListActive;
    private volatile JobStatus nextStatus = JobStatus.succeeded();

    /**
     * Creates the task.
     *
     * @param orders  the local records
     * @param partner the stub partner, which holds the records a collected page returns
     */
    public PartnerBulkStatusTask(LocalOrderRepository orders, StubPartner partner) {
        this.orders = orders;
        this.partner = partner;
    }

    /**
     * Makes the partner accept a submit but the caller never see the answer - the crash-between-submit
     * -and-commit case.
     *
     * @param swallow whether to swallow the response
     */
    public void swallowSubmitResponse(boolean swallow) {
        this.swallowSubmitResponse = swallow;
    }

    /**
     * Whether the partner offers a listing of its active jobs.
     *
     * @param supported whether {@code listActive()} answers
     */
    public void supportsListActive(boolean supported) {
        this.supportsListActive = supported;
    }

    /**
     * What the partner will say the next time a job is polled.
     *
     * @param status the status to report
     */
    public void reports(JobStatus status) {
        this.nextStatus = status;
    }

    /** Handles the partner has been asked to cancel. */
    public List<String> cancelled() {
        return List.copyOf(cancelled);
    }

    /** Every job the partner believes it is running. */
    public Map<String, Job> remoteJobs() {
        return Map.copyOf(remoteJobs);
    }

    /** Forgets everything, including jobs the partner thinks are running. */
    public void reset() {
        remoteJobs.clear();
        cancelled.clear();
        handles.set(0);
        swallowSubmitResponse = false;
        supportsListActive = false;
        nextStatus = JobStatus.succeeded();
    }

    @Override
    public String name() {
        return "partner-bulk-status";
    }

    @Override
    public DemandProvider<LocalOrder, String> demand() {
        return new DemandProvider<>() {

            @Override
            @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
            public List<LocalOrder> demand(DemandRequest request) {
                return orders.findByStatusNot("SETTLED");
            }

            @Override
            @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
            public Optional<LocalOrder> byKey(String key) {
                return orders.findByExternalId(key);
            }
        };
    }

    @Override
    public Function<LocalOrder, String> localKey() {
        return LocalOrder::getExternalId;
    }

    @Override
    public KeyCodec<String> keyCodec() {
        return KeyCodec.ofString();
    }

    @Override
    public Fetcher<String, BillingRecord> fetcher() {
        return new Fetcher.JobFetcher<String, String, BillingRecord>() {

            @Override
            public String submit(Collection<String> keys, IdempotencyKey idempotencyKey) {
                String handle = "job-" + handles.incrementAndGet();
                remoteJobs.put(handle, new Job(handle, idempotencyKey.value(), List.copyOf(keys)));
                if (swallowSubmitResponse) {
                    // The partner accepted it; the caller is about to die before writing the handle
                    // down. This is the whole reason the row is committed before the call.
                    throw new IllegalStateException("connection reset before the response arrived");
                }
                return handle;
            }

            @Override
            public JobStatus poll(String handle) {
                if (!remoteJobs.containsKey(handle)) {
                    return JobStatus.expired();
                }
                return nextStatus;
            }

            @Override
            public PageResult<BillingRecord> collect(String handle, String cursor) {
                Job job = remoteJobs.get(handle);
                if (job == null) {
                    return PageResult.empty();
                }
                List<BillingRecord> found = new ArrayList<>();
                job.keys().forEach(key -> partner.fetchOne(key).ifPresent(found::add));
                return PageResult.last(found);
            }

            @Override
            public String keyOf(BillingRecord record) {
                return record.orderId();
            }

            @Override
            public KeyCodec<String> handleCodec() {
                return KeyCodec.ofString();
            }

            @Override
            public void cancel(String handle) {
                cancelled.add(handle);
                remoteJobs.remove(handle);
            }

            @Override
            public Optional<List<ActiveJob<String>>> listActive() {
                if (!supportsListActive) {
                    return Optional.empty();
                }
                return Optional.of(remoteJobs.values().stream()
                        .map(job -> new ActiveJob<>(job.handle(), new IdempotencyKey(job.idempotencyKey())))
                        .toList());
            }
        };
    }

    @Override
    public Reconciler<LocalOrder, BillingRecord> reconciler() {
        return (local, external, context) -> {
            if (external.status().equals(local.getStatus())) {
                return ReconcileResult.unchanged();
            }
            local.setStatus(external.status());
            local.setSourceTimestamp(external.changedAt());
            local.setSyncCount(local.getSyncCount() + 1);
            orders.save(local);
            return ReconcileResult.applied("status -> " + external.status());
        };
    }

    @Override
    public Class<BillingRecord> externalType() {
        return BillingRecord.class;
    }

    @Override
    public ExternalStamp stampOf(BillingRecord record) {
        return ExternalStamp.ofTimestamp(record.changedAt() == null ? Instant.now() : record.changedAt());
    }

    /**
     * A job the partner believes it is running.
     *
     * @param handle         the partner's handle
     * @param idempotencyKey the key it was submitted under
     * @param keys           the correlation keys it covers
     */
    public record Job(String handle, String idempotencyKey, List<String> keys) {
    }
}
