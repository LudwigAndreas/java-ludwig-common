package ru.ludwigandreas.reconciliation.admin.service;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * A run record as the platform's operation envelope, plus the run's own facts.
 *
 * <p>The module's edge, which the long-running-operations contract prescribes: the engine records when a run started,
 * when it finished and how it ended, and only here does that become an {@link OperationStatus}.
 */
public final class RunViews {

    private RunViews() {
    }

    /**
     * The envelope status of a run: pending until it starts, running until it finishes, then by its outcome.
     *
     * @param run the run
     * @return the status; never cancelled or expired
     */
    public static OperationStatus statusOf(RunSnapshot run) {
        if (run.finishedAt() == null) {
            return run.startedAt() == null ? OperationStatus.PENDING : OperationStatus.RUNNING;
        }
        return run.outcome() == RunOutcome.SUCCESS ? OperationStatus.SUCCEEDED : OperationStatus.FAILED;
    }

    /**
     * The run's envelope.
     *
     * @param run  the run
     * @param href the run's own resource, which a success points at as its result
     * @return the envelope
     */
    public static OperationResponse toOperation(RunSnapshot run, String href) {
        OperationStatus status = statusOf(run);
        return OperationResponse.builder()
                .id(run.id().toString())
                .status(status)
                .detail(run.kind().name())
                .submittedAt(run.submittedAt())
                .startedAt(run.startedAt())
                .finishedAt(run.finishedAt())
                .result(status == OperationStatus.SUCCEEDED ? OperationResult.at(href) : null)
                .failure(status == OperationStatus.FAILED
                        ? OperationFailure.of(run.failureCode() == null ? "unknown" : run.failureCode())
                        : null)
                .correlationId(run.correlationId())
                .build();
    }

    /**
     * The response body: the envelope, and what the run was and counted.
     *
     * @param run  the run
     * @param href its resource
     * @return the view
     */
    public static RunView toView(RunSnapshot run, String href) {
        return new RunView(toOperation(run, href), run.id(), run.task(), run.tier().name(),
                run.kind().name(), run.triggeredBy(), run.instance(), run.submittedAt(),
                run.demanded(), run.fetched(), run.produced(), run.resultJson());
    }

    /**
     * One run.
     *
     * @param operation   the envelope
     * @param id          the run id, which is also the id in the run's audit events
     * @param task        the task
     * @param tier        the tier
     * @param kind        what started it
     * @param triggeredBy who asked, or null for a scheduled run
     * @param instance    the replica that ran it
     * @param submittedAt when it was asked for
     * @param demanded    keys the demand produced
     * @param fetched     records the partner returned
     * @param produced    rows staged or applied
     * @param result      a dry run's report or a backfill's counts, as recorded; keys and counts only
     */
    public record RunView(OperationResponse operation, UUID id, String task, String tier, String kind,
                          String triggeredBy, String instance, Instant submittedAt, Integer demanded,
                          Integer fetched, Integer produced, @JsonRawValue String result) {
    }
}
