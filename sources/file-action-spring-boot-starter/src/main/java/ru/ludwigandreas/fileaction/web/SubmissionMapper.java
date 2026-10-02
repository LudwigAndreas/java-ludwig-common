package ru.ludwigandreas.fileaction.web;

import java.util.List;
import ru.ludwigandreas.fileaction.api.ActionMode;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;

/**
 * Turns a submission row into the platform envelope and this module's own response.
 *
 * <h2>This is the module's edge, and that is deliberate</h2>
 *
 * <p>The long-running-operation contract says there is no shared operation table and each module maps onto the
 * envelope at its edge. This class is that edge: it is the only place that knows both the submission row and
 * {@code OperationResponse}, which is what keeps the mapping out of the entity, out of the service, and out of
 * {@code web-core}.
 */
public final class SubmissionMapper {

    private SubmissionMapper() {
    }

    /**
     * The platform envelope for a submission.
     *
     * @param submission   the row
     * @param resultHref   where the result is fetched from on a terminal success, or null when there is nothing
     *                     to fetch
     * @return the envelope
     */
    public static OperationResponse toOperation(SubmissionSnapshot submission, String resultHref) {
        FileActionState state = submission.state();
        return OperationResponse.builder()
                .id(submission.id().toString())
                .status(state.status())
                // The domain state, in the field the contract provides for exactly this. UPLOADED and VALIDATED
                // are both PENDING, and the difference between them is the whole confirm feature - a client
                // showing a spinner for one and a button for the other cannot tell them apart otherwise.
                .detail(state.name())
                .progress(progressOf(submission))
                .submittedAt(submission.submittedAt())
                .startedAt(submission.startedAt())
                .finishedAt(submission.finishedAt())
                .result(resultFor(submission, resultHref))
                .failure(failureOf(submission))
                .correlationId(submission.correlationId())
                .build();
    }

    /**
     * The module's own response, with the envelope nested.
     *
     * @param submission    the row
     * @param action        the resolved action, for the mode
     * @param rejectsStored how many rejects the paged endpoint can serve
     * @param resultHref    where the result is, or null
     * @return the response
     */
    public static SubmissionResponse toResponse(SubmissionSnapshot submission,
                                                ResolvedAction<?> action, long rejectsStored,
                                                String resultHref) {
        return SubmissionResponse.builder()
                .operation(toOperation(submission, resultHref))
                .id(submission.id())
                .action(submission.action())
                .state(submission.state())
                .filename(submission.filename())
                .format(submission.format())
                .sheet(submission.sheet())
                .rowsRead(submission.rowsRead())
                .rowsApplied(submission.rowsApplied())
                .rowsRejected(submission.rowsRejected())
                .rowsSkipped(submission.rowsSkipped())
                .rejectsStored(rejectsStored)
                .awaitingConfirmation(submission.state() == FileActionState.VALIDATED
                        && action.mode() == ActionMode.CONFIRM)
                .confirmBy(submission.state() == FileActionState.VALIDATED
                        ? submission.expiresAt() : null)
                .errorReport(submission.errorReportUri() != null)
                .submittedAt(submission.submittedAt())
                .finishedAt(submission.finishedAt())
                .build();
    }

    /**
     * How far along a submission is.
     *
     * <p>The denominator is left null while the file is being read, because until the last row has been seen
     * nobody knows how many there are - which is exactly the case {@code OperationProgress}'s nullable total
     * exists for. Reporting a guessed total would make a progress bar run backwards.
     */
    private static OperationProgress progressOf(SubmissionSnapshot submission) {
        if (submission.rowsRead() == 0) {
            return null;
        }
        if (submission.state().isTerminal()) {
            return OperationProgress.of(submission.rowsApplied() + submission.rowsRejected()
                    + submission.rowsSkipped(), submission.rowsRead(), "rows");
        }
        return OperationProgress.of(submission.rowsRead(), "rows")
                .inPhase(submission.state().name());
    }

    /**
     * The result link, on a terminal success.
     *
     * <p>{@code OperationResponses} refuses a terminal success with no result, so a succeeded submission always
     * has one. For an import the result is the submission itself - there is no file to fetch - which is why the
     * caller passes the href rather than this class inventing one: only the controller knows the path it is
     * mounted at.
     */
    private static OperationResult resultFor(SubmissionSnapshot submission, String resultHref) {
        if (!submission.state().isTerminal() || submission.state() == FileActionState.REJECTED) {
            return null;
        }
        if (submission.state() == FileActionState.EXPIRED) {
            // Deliberately none. EXPIRED means retention removed the artifacts, so a link would be a link to
            // nothing - which is the failure mode that state exists to prevent.
            return null;
        }
        if (resultHref == null) {
            return null;
        }
        return submission.errorReportUri() == null
                ? OperationResult.at(resultHref)
                : OperationResult.at(resultHref).withAlternatives(
                        List.of(OperationResult.at(resultHref + "/error-report")));
    }

    private static OperationFailure failureOf(SubmissionSnapshot submission) {
        if (submission.failureCode() == null) {
            return null;
        }
        List<String> args = submission.failureArgs() == null ? List.of()
                : List.of(submission.failureArgs().split("\n", -1));
        return OperationFailure.of(submission.failureCode(), args);
    }
}
