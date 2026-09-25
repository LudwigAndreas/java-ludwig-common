package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A stage calls its partner as {@link CallIdentity#REQUESTER} and there is no requester's token to use.
 *
 * <h2>Why this is an error and not a fallback</h2>
 *
 * <p>The tempting behaviour is to fall back to the service account and produce the file anyway. That
 * would defeat the only reason to choose {@code REQUESTER}: the partner's own row-level scoping. The
 * resulting file would contain whatever the partner shows this service - potentially far more than the
 * person who asked may see - and nothing about it would say so. A report that is wrong in that
 * direction is worse than a report that did not appear.
 *
 * <p>It is raised in two places, and the earlier one is the one a user should normally meet.
 * {@code ReportRequestService} refuses the <em>request</em> when its estimate says the run would be
 * deferred, so the caller is told immediately, with a row count they can narrow. The planner raises it
 * again on a deferred attempt, for the cases an estimate cannot cover - a subscription, a saved
 * configuration whose data grew, a reclaimed attempt - where it becomes the run's terminal failure
 * code rather than a retryable one: waiting will not produce a request thread.
 *
 * <p>{@code CONFLICT} rather than {@code FORBIDDEN}: the requester is not forbidden anything. The
 * report as configured cannot be produced the way it was asked for, which is a statement about the
 * request and the definition together.
 */
@Getter
public class ReportIdentityUnavailableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String definitionKey;

    private final String stageName;

    /**
     * @param definitionKey the report
     * @param stageName     the stage that needs the requester's token, named so an operator reading the
     *                      run row knows which partner to look at rather than which report
     */
    public ReportIdentityUnavailableException(String definitionKey, String stageName) {
        super(ProblemStatus.CONFLICT, ExportProblemCodes.IDENTITY_UNAVAILABLE, definitionKey, stageName);
        this.definitionKey = definitionKey;
        this.stageName = stageName;
        withProperty("definition", definitionKey);
        withProperty("stage", stageName);
    }
}
