package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.ReconcileContext;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordKind;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies one staged record, in its own transaction.
 *
 * <p>A separate bean from {@link ApplyService} rather than a method on it, and deliberately so:
 * Spring's proxy-based transaction management does not intercept a bean calling its own methods, so a
 * {@code @Transactional} apply invoked from a loop in the same class would silently run in the
 * caller's transaction - or in none - and the per-record isolation this module is built on would be
 * gone with no symptom until the first record failed and took its whole batch with it.
 *
 * <h2>The two guard rails applied here</h2>
 *
 * <p><b>Idempotency.</b> If the normalized payload hashes to what was already applied for this key,
 * nothing is written: no update, no {@code updated_at} churn, no audit row, no downstream event. On a
 * task that sweeps a large catalogue every few hours, this is the difference between a quiet database
 * and one whose replication lag is dominated by rewriting rows to their existing values.
 *
 * <p><b>Stale-write protection.</b> If the arriving record is older, by the partner's own timestamp,
 * than what has already been applied for this key, it is rejected. Out-of-order responses are normal -
 * a retried call that was slow, a batch that overtook a per-item fetch - and applying the older one
 * last silently regresses the local record. That bug produces no error, no failed metric and no log
 * line; it is the reason this check is in the engine rather than left to each reconciler to remember.
 */
public class RecordApplier {

    /** Single-row page for the "newest settled record for this key" lookup. */
    private static final PageRequest NEWEST = PageRequest.of(0, 1);

    private static final Logger log = LoggerFactory.getLogger(RecordApplier.class);

    private final SyncInboxRecordRepository repository;
    private final PayloadCodec payloadCodec;
    private final ReconciliationMetrics metrics;
    private final ReconciliationAuditLogger auditLogger;

    /**
     * Creates the applier.
     *
     * @param repository   the staging table
     * @param payloadCodec payload deserialization
     * @param metrics      instrumentation
     * @param auditLogger  the audit trail
     */
    public RecordApplier(SyncInboxRecordRepository repository,
                         PayloadCodec payloadCodec,
                         ReconciliationMetrics metrics,
                         ReconciliationAuditLogger auditLogger) {
        this.repository = repository;
        this.payloadCodec = payloadCodec;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * Applies one record.
     *
     * <p>{@code REQUIRES_NEW} because the caller may itself be inside a transaction - an actuator
     * operation, a test - and this record's outcome must be committed on its own merits either way.
     * One record failing must never roll back another record's success.
     *
     * @param <I>      local record type
     * @param <K>      correlation key type
     * @param <O>      external record type
     * @param task     the task
     * @param recordId the staged record
     * @return the status it settled into
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <I, K, O> SyncRecordStatus apply(RegisteredTask<I, K, O> task, UUID recordId) {
        SyncInboxRecord record = repository.getByIdOrThrow(recordId);
        TaskSettings settings = task.settings();
        record.setAttempts(record.getAttempts() + 1);
        try {
            SyncRecordStatus status = decide(task, record);
            settle(record, status, null);
            metrics.recordRecordOutcome(settings.name(), status.name().toLowerCase(Locale.ROOT));
            audit(settings, record, status, record.getLastError());
            return status;
        } catch (Exception e) {
            // A reconciler is application code calling into a domain: anything can come out of it,
            // and a single record's failure must settle that record rather than the run.
            return recordFailure(task, record, e);
        }
    }

    private <I, K, O> SyncRecordStatus decide(RegisteredTask<I, K, O> task, SyncInboxRecord record) {
        K key = task.task().keyCodec().decode(record.getCorrelationKey());
        Optional<I> local = task.task().demand().byKey(key);
        if (local.isEmpty()) {
            // The local record was deleted between the fetch and the apply. There is nothing to apply
            // external state to, and that is an ordinary outcome rather than a failure.
            record.setLastError("The local record no longer exists");
            return SyncRecordStatus.UNCHANGED;
        }

        SyncInboxRecord newest = findNewestSettled(record);
        if (record.getKind() == SyncRecordKind.MISSING) {
            return map(task.task().reconciler()
                    .reconcileMissing(local.get(), context(record, newest)), record);
        }

        if (record.getPayloadHash() != null && newest != null
                && record.getPayloadHash().equals(newest.getPayloadHash())) {
            record.setLastError(null);
            return SyncRecordStatus.UNCHANGED;
        }

        ExternalStamp arriving = stampOf(record);
        ExternalStamp applied = stampOf(newest);
        if (arriving.isOlderThan(applied)) {
            record.setLastError("External state dated " + arriving.timestamp()
                    + " is older than the state already applied, dated " + applied.timestamp());
            return SyncRecordStatus.REJECTED;
        }

        O external = payloadCodec.deserialize(record.getPayload(), task.task().externalType());
        return map(task.task().reconciler()
                .reconcile(local.get(), external, context(record, newest)), record);
    }

    private SyncRecordStatus map(ReconcileResult result, SyncInboxRecord record) {
        if (result instanceof ReconcileResult.Applied applied) {
            // The detail is what changed, not an error - the column carries the last thing worth
            // saying about the row, and for a successful apply that is the description of the change.
            record.setLastError(applied.detail());
            return SyncRecordStatus.APPLIED;
        }
        if (result instanceof ReconcileResult.Unchanged) {
            record.setLastError(null);
            return SyncRecordStatus.UNCHANGED;
        }
        if (result instanceof ReconcileResult.Rejected rejected) {
            record.setLastError(rejected.reason());
            return SyncRecordStatus.REJECTED;
        }
        ReconcileResult.Deferred deferred = (ReconcileResult.Deferred) result;
        record.setLastError(deferred.reason());
        record.setNextAttemptAt(Instant.now().plus(deferred.retryAfter()));
        return SyncRecordStatus.DEFERRED;
    }

    private <I, K, O> SyncRecordStatus recordFailure(RegisteredTask<I, K, O> task,
                                                     SyncInboxRecord record,
                                                     Exception cause) {
        TaskSettings settings = task.settings();
        String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        boolean exhausted = record.getAttempts() >= settings.retry().maxAttempts();
        SyncRecordStatus status = exhausted ? SyncRecordStatus.QUARANTINED : SyncRecordStatus.FAILED;
        if (!exhausted) {
            BackoffCalculator backoff = new BackoffCalculator(settings.retry().backoff());
            record.setNextAttemptAt(Instant.now().plus(backoff.nextDelay(record.getAttempts())));
        }
        settle(record, status, reason);
        metrics.recordRecordOutcome(settings.name(), status.name().toLowerCase(Locale.ROOT));
        audit(settings, record, status, reason);
        if (exhausted) {
            log.warn("Task '{}' quarantined record {} (key {}) after {} attempt(s)",
                    settings.name(), record.getId(), record.getCorrelationKey(), record.getAttempts(), cause);
        } else {
            log.debug("Task '{}' will retry record {} (key {})",
                    settings.name(), record.getId(), record.getCorrelationKey(), cause);
        }
        return status;
    }

    /**
     * Writes the outcome onto the row.
     *
     * <p>{@code DEFERRED} and {@code FAILED} keep their claim released but are not settled: they are
     * still in the pipeline, which is what the backlog gauge and the freshness lag should keep
     * counting them as.
     */
    private void settle(SyncInboxRecord record, SyncRecordStatus status, String error) {
        record.setStatus(status);
        record.setLockedAt(null);
        record.setLockedBy(null);
        if (error != null) {
            record.setLastError(error);
        }
        if (status != SyncRecordStatus.FAILED && status != SyncRecordStatus.DEFERRED) {
            record.setSettledAt(Instant.now());
        }
    }

    private SyncInboxRecord findNewestSettled(SyncInboxRecord record) {
        List<SyncInboxRecord> newest = repository.findNewestSettled(
                record.getTaskName(), record.getCorrelationKey(), NEWEST);
        return newest.isEmpty() ? null : newest.get(0);
    }

    private ReconcileContext context(SyncInboxRecord record, SyncInboxRecord newest) {
        return new ReconcileContext(record.getTaskName(), record.getRunId(), record.getAttempts(),
                stampOf(record), stampOf(newest), record.getReceivedAt());
    }

    private static ExternalStamp stampOf(SyncInboxRecord record) {
        if (record == null) {
            return ExternalStamp.none();
        }
        return new ExternalStamp(record.getExternalVersion(), record.getExternalTimestamp());
    }

    private void audit(TaskSettings settings, SyncInboxRecord record, SyncRecordStatus status, String detail) {
        if (!settings.audit().enabled()) {
            return;
        }
        auditLogger.record(AuditEvent.builder(settings.name(), AuditEvent.Category.RECORD,
                        "record." + status.name().toLowerCase(Locale.ROOT))
                .subject(record.getCorrelationKey())
                .transition(SyncRecordStatus.PROCESSING, status)
                .detail(detail == null ? record.getLastError() : detail)
                .runId(record.getRunId())
                .correlationId(record.getCorrelationId())
                .build());
    }
}
