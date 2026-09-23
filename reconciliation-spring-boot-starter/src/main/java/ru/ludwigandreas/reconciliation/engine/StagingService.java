package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.NotFoundPolicy;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordKind;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes what a fetch produced into {@code sync_inbox_record}.
 *
 * <p>The boundary between the two phases. Everything before this point has talked to a partner and
 * holds results in memory; everything after it works from rows and can be interrupted, restarted and
 * resumed. Staging is therefore deliberately dumb: it classifies outcomes and writes them, and makes
 * no decision that could fail in an interesting way, because a failure here loses a whole fetch.
 */
public class StagingService {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);

    private final SyncInboxRecordRepository repository;
    private final PayloadCodec payloadCodec;
    private final ReconciliationMetrics metrics;
    private final ReconciliationAuditLogger auditLogger;

    /**
     * Creates the service.
     *
     * @param repository   the staging table
     * @param payloadCodec serialization and hashing
     * @param metrics      instrumentation
     * @param auditLogger  the audit trail
     */
    public StagingService(SyncInboxRecordRepository repository,
                          PayloadCodec payloadCodec,
                          ReconciliationMetrics metrics,
                          ReconciliationAuditLogger auditLogger) {
        this.repository = repository;
        this.payloadCodec = payloadCodec;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * Stages a batch of fetch outcomes.
     *
     * <p>One transaction for the whole batch, not one per row. A staging write cannot fail for a
     * reason specific to one record - the payload was already serialized by the fetch walker, so
     * anything that fails here fails for all of them - and a transaction per row would multiply the
     * write cost of a 5,000-record run by the round-trip latency.
     *
     * @param <K>      correlation key type
     * @param <O>      external record type
     * @param task     the task
     * @param outcomes what the fetch produced
     * @param context  the run
     * @param jobId    the asynchronous job these came from, or null
     * @return how many rows were written
     */
    @Transactional
    public <K, O> int stage(RegisteredTask<?, K, O> task,
                            List<FetchOutcome<K, O>> outcomes,
                            RunContext context,
                            java.util.UUID jobId) {
        TaskSettings settings = task.settings();
        BackoffCalculator backoff = new BackoffCalculator(settings.retry().backoff());
        List<SyncInboxRecord> rows = new ArrayList<>(outcomes.size());
        int found = 0;

        for (FetchOutcome<K, O> outcome : outcomes) {
            String key = task.task().keyCodec().encode(outcome.key());
            if (outcome instanceof FetchOutcome.Found<K, O> hit) {
                rows.add(stageFound(task, hit, key, context, jobId));
                found++;
            } else if (outcome instanceof FetchOutcome.NotFound<K, O>) {
                stageNotFound(task, key, context, jobId, backoff).ifPresent(rows::add);
            } else if (outcome instanceof FetchOutcome.Failed<K, O> failure) {
                rows.add(stageFailure(task, failure, key, context, backoff));
            }
        }

        metrics.recordFetched(settings.name(), found);
        if (!rows.isEmpty()) {
            repository.saveAll(rows);
        }
        // Settling any outstanding failure ticket is what lets a key that has started working again
        // stop being suppressed; without it the key stays skipped until its budget runs out.
        outcomes.stream()
                .filter(FetchOutcome.Found.class::isInstance)
                .forEach(outcome -> clearFetchFailures(settings.name(),
                        task.task().keyCodec().encode(outcome.key())));
        return rows.size();
    }

    private <K, O> SyncInboxRecord stageFound(RegisteredTask<?, K, O> task,
                                              FetchOutcome.Found<K, O> outcome,
                                              String key,
                                              RunContext context,
                                              java.util.UUID jobId) {
        ExternalStamp stamp = task.task().stampOf(outcome.record());
        String payload = payloadCodec.serialize(outcome.record());
        SyncInboxRecord row = newRow(task.settings(), key, SyncRecordKind.RECORD, context, jobId);
        row.setPayload(payload);
        row.setPayloadHash(payloadCodec.hashJson(payload));
        row.setExternalVersion(stamp.version());
        row.setExternalTimestamp(stamp.timestamp());
        return row;
    }

    /**
     * Applies the task's not-found policy.
     *
     * <p>{@code ignore} writes nothing at all - a key the partner has forgotten is a fact about their
     * data, and a row per poll per forgotten key would be the largest table in the database within a
     * week. The count still goes to a metric, which is where an operator can see "the partner has
     * forgotten 4,000 of our ids" as a number rather than as a wave of noise.
     */
    private <K, O> java.util.Optional<SyncInboxRecord> stageNotFound(RegisteredTask<?, K, O> task,
                                                                     String key,
                                                                     RunContext context,
                                                                     java.util.UUID jobId,
                                                                     BackoffCalculator backoff) {
        TaskSettings settings = task.settings();
        NotFoundPolicy policy = settings.notFound();
        metrics.recordNotFound(settings.name(), policy.name().toLowerCase(Locale.ROOT));

        return switch (policy) {
            case IGNORE -> java.util.Optional.empty();
            case MARK_MISSING -> java.util.Optional.of(
                    newRow(settings, key, SyncRecordKind.MISSING, context, jobId));
            case FAIL -> java.util.Optional.of(failureRow(settings, key, context, backoff,
                    "The partner does not know this key, and this task's not-found policy is 'fail'",
                    true));
        };
    }

    private <K, O> SyncInboxRecord stageFailure(RegisteredTask<?, K, O> task,
                                                FetchOutcome.Failed<K, O> outcome,
                                                String key,
                                                RunContext context,
                                                BackoffCalculator backoff) {
        return failureRow(task.settings(), key, context, backoff, outcome.reason(), outcome.retryable());
    }

    /**
     * Builds or continues a key's fetch-failure ticket.
     *
     * <p>Continues an existing one where there is one, so a key that has failed six times is on its
     * seventh attempt rather than its first. Restarting the budget on every run would make the
     * quarantine unreachable and the backoff meaningless.
     */
    private SyncInboxRecord failureRow(TaskSettings settings,
                                       String key,
                                       RunContext context,
                                       BackoffCalculator backoff,
                                       String reason,
                                       boolean retryable) {
        List<SyncInboxRecord> open = repository.findOpenFetchFailures(settings.name(), key);
        SyncInboxRecord row = open.isEmpty()
                ? newRow(settings, key, SyncRecordKind.FETCH_FAILURE, context, null)
                : open.get(0);
        row.setAttempts(row.getAttempts() + 1);
        row.setLastError(reason);
        row.setRunId(context.runId());

        boolean exhausted = !retryable || row.getAttempts() >= settings.retry().maxAttempts();
        if (exhausted) {
            row.setStatus(SyncRecordStatus.QUARANTINED);
            row.setSettledAt(Instant.now());
            metrics.recordRecordOutcome(settings.name(), "fetch_quarantined");
            auditLogger.record(AuditEvent.builder(settings.name(), AuditEvent.Category.RECORD,
                            "fetch.quarantined")
                    .subject(key).detail(reason).runId(context.runId())
                    .correlationId(context.correlationId()).build());
            log.warn("Task '{}' gave up fetching key {} after {} attempt(s): {}",
                    settings.name(), key, row.getAttempts(), reason);
        } else {
            row.setStatus(SyncRecordStatus.FAILED);
            row.setNextAttemptAt(Instant.now().plus(backoff.nextDelay(row.getAttempts())));
            metrics.recordRecordOutcome(settings.name(), "fetch_failed");
        }
        return row;
    }

    private void clearFetchFailures(String taskName, String key) {
        List<SyncInboxRecord> open = repository.findOpenFetchFailures(taskName, key);
        for (SyncInboxRecord row : open) {
            row.setStatus(SyncRecordStatus.UNCHANGED);
            row.setSettledAt(Instant.now());
            row.setLastError(null);
        }
        if (!open.isEmpty()) {
            repository.saveAll(open);
        }
    }

    private SyncInboxRecord newRow(TaskSettings settings,
                                   String key,
                                   SyncRecordKind kind,
                                   RunContext context,
                                   java.util.UUID jobId) {
        SyncInboxRecord row = new SyncInboxRecord();
        row.setTaskName(settings.name());
        row.setKind(kind);
        row.setCorrelationKey(key);
        row.setReceivedAt(Instant.now());
        row.setStatus(SyncRecordStatus.STAGED);
        row.setMaxAttempts(settings.retry().maxAttempts());
        row.setNextAttemptAt(Instant.now());
        row.setRunId(context.runId());
        row.setJobId(jobId);
        row.setCorrelationId(context.correlationId());
        return row;
    }
}
