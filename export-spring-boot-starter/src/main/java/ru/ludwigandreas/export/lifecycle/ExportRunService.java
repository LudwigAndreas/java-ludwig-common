package ru.ludwigandreas.export.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.export.api.RunStatus;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.ReportOutput;
import ru.ludwigandreas.export.engine.ReportRunResult;
import ru.ludwigandreas.export.entity.ExportReportOutput;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.event.ReportEventPublisher;
import ru.ludwigandreas.export.event.ReportReadyEvent;
import ru.ludwigandreas.export.exception.ExportQuotaExceededException;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.job.core.backoff.BackoffPolicy;
import ru.ludwigandreas.webcore.problem.LocalizedException;

/**
 * Every write to a run row, in one place, so the state machine has one implementation.
 *
 * <h2>Why the transitions are here and not on the entity</h2>
 *
 * <p>Because each of them is a transaction boundary as much as a state change: accepting a run
 * checks a quota and takes a unique key, finishing one writes the outputs in the same transaction as
 * the status, and failing one has to decide between a retry and a terminal state. An entity method
 * could express the field changes and none of the rest, so the interesting half would end up
 * scattered across the callers - which is how two callers come to disagree about what "failed" does
 * to {@code next_attempt_at}.
 *
 * <h2>Quotas are checked, not enforced</h2>
 *
 * <p>The count and the insert are not atomic, so two requests arriving in the same millisecond can
 * both pass a quota of one. That is accepted deliberately: making it exact would need a lock per user
 * on the hot path of every report request, to prevent a third concurrent run rather than a thousand.
 * What the quota is actually for is the loop - a client retrying a slow report on a timeout, queuing
 * a second million-row run behind the first - and against that a check is exactly as effective as a
 * lock. The idempotency key is what stops the specific case of the same request twice, and that one
 * <em>is</em> enforced, by a unique constraint.
 */
@Slf4j
public class ExportRunService {

    /** What a finished run's progress reads, so a client polling it sees it reach the end. */
    private static final int COMPLETE_PERCENT = 100;

    private final ExportReportRunRepository runs;
    private final ExportReportOutputRepository outputs;
    private final ExportProperties properties;
    private final Clock clock;
    private final BackoffCalculator backoff;

    /**
     * Where "a report is ready" is announced.
     *
     * <p>Called from inside {@link #recordSuccess}, which is the whole reason the outbox exists: the
     * event and the SUCCEEDED status commit together, so there is no window in which the file is
     * finished and nobody will ever be told, and none in which somebody is told about a run that
     * rolled back.
     */
    private final ReportEventPublisher events;

    public ExportRunService(ExportReportRunRepository runs, ExportReportOutputRepository outputs,
                            ExportProperties properties, Clock clock, ReportEventPublisher events) {
        this.runs = runs;
        this.outputs = outputs;
        this.properties = properties;
        this.clock = clock;
        this.events = events == null ? ReportEventPublisher.NONE : events;
        this.backoff = new BackoffCalculator(BackoffPolicy.of(
                properties.getPoller().getRetryInitialDelay(), 2.0,
                properties.getPoller().getRetryMaxDelay()));
    }

    /**
     * Accepts a run, or hands back the one this request already produced.
     *
     * <p>Returning the existing run rather than refusing is what makes a client's own HTTP retry
     * harmless: the second call gets the first call's run id and can poll it, instead of a 409 it has
     * to interpret. A caller that wants a genuinely new run supplies a different idempotency key,
     * which is the only way to say "yes, again" unambiguously.
     */
    @Transactional
    public ExportReportRun submit(ExportReportRun run) {
        Optional<ExportReportRun> existing = runs.findByIdempotencyKey(run.getIdempotencyKey());
        if (existing.isPresent()) {
            log.debug("Report request {} was already accepted as run {}", run.getIdempotencyKey(),
                    existing.get().getId());
            return existing.get();
        }
        checkQuota(run.getRequester());
        run.setStatus(RunStatus.PENDING);
        run.setNextAttemptAt(clock.instant());
        run.setMaxAttempts(properties.getPoller().getMaxAttempts());
        return runs.save(run);
    }

    private void checkQuota(String requester) {
        ExportProperties.Quota quota = properties.getQuota();
        long inFlight = runs.countByRequesterAndStatusIn(requester,
                List.of(RunStatus.PENDING, RunStatus.RUNNING));
        if (inFlight >= quota.getConcurrentRunsPerUser()) {
            throw new ExportQuotaExceededException("concurrent", quota.getConcurrentRunsPerUser());
        }
        Instant dayAgo = clock.instant().minus(Duration.ofDays(1));
        long today = runs.countByRequesterAndCreatedAtAfter(requester, dayAgo);
        if (today >= quota.getDailyRunsPerUser()) {
            throw new ExportQuotaExceededException("daily", quota.getDailyRunsPerUser());
        }
    }

    /**
     * Records a finished run and its files, in one transaction.
     *
     * <p>One transaction because the two halves are one fact. A run marked SUCCEEDED whose output
     * rows did not commit is a run a caller is told to download and cannot, and the reverse - outputs
     * with no successful run - is a set of files nothing will ever expire.
     *
     * <p>Returns the updated row, and that return value is load-bearing rather than a convenience. A
     * synchronous run's caller is answered from whatever this method's caller holds, and the object it
     * passed in was read before the run started: answering from that one reported PENDING and zero
     * rows for a report that had already finished, so the caller was told to poll for a file it could
     * have downloaded immediately.
     *
     * @param runId  the run
     * @param result what the engine produced
     * @return the run as it now stands
     */
    @Transactional
    public ExportReportRun recordSuccess(UUID runId, ReportRunResult result) {
        ExportReportRun run = runs.findById(runId).orElseThrow();
        Instant now = clock.instant();
        run.setStatus(RunStatus.SUCCEEDED);
        run.setRowsWritten(result.rowsWritten());
        run.setPercent(COMPLETE_PERCENT);
        run.setFinishedAt(now);
        run.setDegradedStages(result.degradedStages());
        run.setOmittedSheets(result.omittedSheets());
        clearLease(run);
        Instant expiresAt = now.plus(properties.getSink().getRetention());
        for (ReportOutput produced : result.outputs()) {
            outputs.save(toEntity(runId, produced, expiresAt));
        }
        ExportReportRun saved = runs.save(run);
        announce(saved, result, now, expiresAt);
        return saved;
    }

    /**
     * Announces the finished report, without letting the announcement fail it.
     *
     * <p>The file is written and stored by the time this runs, so there is nothing left to undo -
     * only something left to say. A publisher that threw would roll back a transaction whose work is
     * already on disk, and the run would be retried to produce a file that already exists.
     */
    private void announce(ExportReportRun run, ReportRunResult result, Instant now, Instant expiresAt) {
        try {
            events.reportReady(new ReportReadyEvent(run.getId(), run.getDefinitionKey(),
                    run.getSavedReportId(), run.getRequester(), List.of(),
                    result.outputs().stream()
                            .map(output -> new ReportReadyEvent.ReadyOutput(output.formatId(),
                                    output.fileName(), output.mediaType(),
                                    output.stored().sizeBytes(), output.stored().sha256()))
                            .toList(),
                    result.rowsWritten(), result.degradedStages(), now, expiresAt));
        } catch (RuntimeException e) {
            log.warn("Could not announce report run {} as ready; the file is stored and downloadable"
                    + " regardless", run.getId(), e);
        }
    }

    /**
     * Records a failure, and decides whether it is the last one.
     *
     * <p>A run goes back to PENDING with a backoff while it has attempts left, and becomes terminal
     * when it does not. The failure code and message key are stored either way, so a caller polling a
     * run that is still retrying can see why the last attempt failed rather than only that it has not
     * finished - which is the difference between waiting and fixing the parameters.
     *
     * <p>A {@code LocalizedException} keeps its own code and arguments; anything else is recorded as
     * an internal failure with no detail, for the same reason {@code web-core} does not render one:
     * the message was written for whoever operates the service.
     */
    @Transactional
    public void recordFailure(UUID runId, Throwable failure) {
        ExportReportRun run = runs.findById(runId).orElseThrow();
        Instant now = clock.instant();
        clearLease(run);
        applyFailure(run, failure);
        if (run.getAttempts() >= run.getMaxAttempts()) {
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(now);
            log.warn("Report run {} for {} failed after {} attempt(s): {}", runId,
                    run.getDefinitionKey(), run.getAttempts(), run.getFailureCode());
        } else {
            Duration delay = backoff.nextDelay(run.getAttempts());
            run.setStatus(RunStatus.PENDING);
            run.setNextAttemptAt(now.plus(delay));
            log.info("Report run {} for {} failed on attempt {} of {}; retrying in {}", runId,
                    run.getDefinitionKey(), run.getAttempts(), run.getMaxAttempts(), delay);
        }
        runs.save(run);
    }

    /** Records a cancelled run. Terminal immediately: a cancellation is not something to retry. */
    @Transactional
    public void recordCancelled(UUID runId, long rowsWritten) {
        ExportReportRun run = runs.findById(runId).orElseThrow();
        run.setStatus(RunStatus.CANCELLED);
        run.setRowsWritten(rowsWritten);
        run.setFinishedAt(clock.instant());
        clearLease(run);
        runs.save(run);
    }

    /**
     * Asks a run to stop.
     *
     * <p>Marks the intent rather than stopping anything: the run is executing on some instance, which
     * may not be this one, and the only thing that can stop it is the instance itself at its next
     * window boundary. A PENDING run is cancelled outright, because nothing has started.
     *
     * @return true if the run was in a state where cancelling means anything
     */
    @Transactional
    public boolean requestCancel(UUID runId) {
        ExportReportRun run = runs.findById(runId).orElse(null);
        if (run == null || run.isTerminal()) {
            return false;
        }
        if (run.getStatus() == RunStatus.PENDING) {
            run.setStatus(RunStatus.CANCELLED);
            run.setFinishedAt(clock.instant());
            runs.save(run);
            return true;
        }
        run.setCancelRequested(true);
        runs.save(run);
        return true;
    }

    /**
     * Whether a run has been asked to stop.
     *
     * <p>Read in its own transaction, outside the run's, because the answer is written by a different
     * request on a different connection: a read joined to the run's long transaction would see the
     * snapshot the run started with and could never observe a cancellation at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isCancellationRequested(UUID runId) {
        // A run that has vanished is treated as cancelled: there is nothing left to write the result
        // to, and continuing to walk a million rows for a row somebody deleted helps nobody.
        return runs.findById(runId)
                .map(run -> run.isCancelRequested() || run.getStatus() == RunStatus.CANCELLED)
                .orElse(true);
    }

    /** Records progress, so a caller polling the run sees it move. */
    @Transactional
    public void recordProgress(UUID runId, long rowsWritten, int percent) {
        runs.findById(runId).ifPresent(run -> {
            run.setRowsWritten(rowsWritten);
            run.setPercent(percent);
            runs.save(run);
        });
    }

    private void applyFailure(ExportReportRun run, Throwable failure) {
        if (failure instanceof LocalizedException localized) {
            run.setFailureCode(localized.getCode());
            run.setFailureMessageKey(localized.getCode());
            run.setFailureArguments(java.util.Arrays.stream(localized.getArgs())
                    .map(String::valueOf)
                    .toList());
            return;
        }
        run.setFailureCode("ludwig.web.error.internal");
        run.setFailureMessageKey("ludwig.web.error.internal");
        run.setFailureArguments(List.of());
        log.error("Report run {} failed with an unhandled error", run.getId(), failure);
    }

    private void clearLease(ExportReportRun run) {
        run.setClaimedBy(null);
        run.setClaimedAt(null);
        run.setHeartbeatAt(null);
        run.setLeaseUntil(null);
    }

    private ExportReportOutput toEntity(UUID runId, ReportOutput produced, Instant expiresAt) {
        ExportReportOutput entity = new ExportReportOutput();
        entity.setRunId(runId);
        entity.setFormatId(produced.formatId());
        entity.setSinkUri(produced.stored().uri());
        entity.setFileName(produced.fileName());
        entity.setMediaType(produced.mediaType());
        entity.setSizeBytes(produced.stored().sizeBytes());
        entity.setSha256(produced.stored().sha256());
        entity.setExpiresAt(expiresAt);
        return entity;
    }
}
