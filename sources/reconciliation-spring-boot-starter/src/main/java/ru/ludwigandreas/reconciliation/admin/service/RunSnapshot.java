package ru.ludwigandreas.reconciliation.admin.service;

import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.entity.RunKind;
import ru.ludwigandreas.reconciliation.entity.SyncRun;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;

/**
 * A run record as the administrative surface sees it, detached from the persistence model.
 *
 * @param id            the run id, which the run's audit events also carry
 * @param task          the task
 * @param tier          the tier
 * @param kind          what started it
 * @param triggeredBy   who asked, or null for a scheduled run
 * @param instance      the replica that ran it
 * @param correlationId the run's correlation id
 * @param submittedAt   when it was asked for
 * @param startedAt     when it started, or null
 * @param finishedAt    when it finished, or null
 * @param outcome       how it ended, or null while it has not
 * @param failureCode   why it failed, or null
 * @param demanded      keys the demand produced
 * @param fetched       records the partner returned
 * @param produced      rows staged or applied
 * @param resultJson    a dry run's report or a backfill's counts, as recorded
 */
public record RunSnapshot(UUID id, String task, DemandTier tier, RunKind kind, String triggeredBy, String instance,
                          String correlationId, Instant submittedAt, Instant startedAt, Instant finishedAt,
                          RunOutcome outcome, String failureCode, Integer demanded, Integer fetched,
                          Integer produced, String resultJson) {

    static RunSnapshot of(SyncRun run) {
        return new RunSnapshot(run.getId(), run.getTaskName(), run.getTier(), run.getKind(), run.getTriggeredBy(),
                run.getInstance(), run.getCorrelationId(), run.getSubmittedAt(), run.getStartedAt(),
                run.getFinishedAt(), run.getOutcome(), run.getFailureCode(), run.getDemanded(), run.getFetched(),
                run.getProduced(), run.getResultJson());
    }

    /** A run that started but whose record could not be written, so all that is known is that it was submitted. */
    static RunSnapshot pending(UUID id, String task, DemandTier tier, RunKind kind) {
        return new RunSnapshot(id, task, tier, kind, null, null, null, Instant.now(), null, null, null, null, null,
                null, null, null);
    }
}
