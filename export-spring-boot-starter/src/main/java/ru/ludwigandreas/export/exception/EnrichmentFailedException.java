package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A partner failed and the stage's policy was to fail the report.
 *
 * <p>Reached only after the stage's REST client has exhausted its own retry budget and its circuit
 * breaker has had its say, so this is not a transient condition the engine should try again - it is
 * the platform's considered verdict that the partner is not answering. The run's own retry, with its
 * own backoff, is the layer that decides whether to try the whole report again later.
 *
 * <p>The default policy, and deliberately so: a report is read as a statement of fact, and a
 * partially-enriched file that does not say so is worse than no file. A definition that would rather
 * have the file says {@code FailurePolicy.DEGRADE}, and then every cell, the run record, the
 * metadata sheet and the download response say which stage was incomplete.
 */
@Getter
public class EnrichmentFailedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String stageName;

    /**
     * Reports a stage that could not be completed.
     *
     * @param stageName the stage, published as {@code stage}
     * @param cause     the partner failure, kept for the log and never rendered to the caller
     */
    public EnrichmentFailedException(String stageName, Throwable cause) {
        super(ProblemStatus.BAD_GATEWAY, ExportProblemCodes.ENRICHMENT_FAILED, cause, stageName);
        this.stageName = stageName;
        withProperty("stage", stageName);
    }
}
