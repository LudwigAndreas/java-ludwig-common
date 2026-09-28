package ru.ludwigandreas.export.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * What a caller sees about a run.
 *
 * <p>Carries no parameters and no principal snapshot. Those are on the row for the audit trail, and
 * echoing them back would put a requester's own parameters - which may be PII - into a response that
 * a proxy will log. What a caller needs in order to act is the status, the progress and, when it
 * failed, the code; all three are here.
 *
 * <h2>The envelope is added, nothing is taken away</h2>
 *
 * <p>{@link #operation()} is {@code web-core}'s platform envelope for a long-running operation, and
 * it is carried <em>alongside</em> this module's own members rather than instead of them. Every
 * field a client reads today still reads the same; what is new is a shape that is identical across
 * every long-running operation on this platform, so a client that polls reports and notifications
 * can do it with one piece of code.
 *
 * <p>The duplication between {@code status} and {@code operation.status} is deliberate and is the
 * price of not breaking a published API. They cannot drift: both are projected from the same row in
 * {@link #of}, and {@code status} is now the shared {@link OperationStatus} rather than an enum of
 * this module's own, so there is one set of words even in the duplicated member.
 *
 * @param id             the run
 * @param definitionKey  which report
 * @param status         where it is in its life, in the platform vocabulary
 * @param operation      the platform envelope: progress, timestamps, result links, failure
 * @param rowsWritten    how many rows have reached a file
 * @param percent        0-100, written by the run rather than interpolated by the client
 * @param attempts       how many times it has been tried
 * @param degraded       whether an enrichment stage was incomplete; every download of it says so too
 * @param degradedStages which stages, so a consumer can say what is missing rather than that
 *                       something is
 * @param omittedSheets  sheets left out of at least one output under {@code PRIMARY_ONLY}
 * @param formats        the formats this run produces
 * @param failureCode    the problem code of the last failure, or null
 * @param startedAt      when it first started executing
 * @param finishedAt     when it reached a terminal state
 */
@Schema(description = "The state of a report run")
public record ReportRunResponse(
        UUID id,
        String definitionKey,
        OperationStatus status,
        OperationResponse operation,
        long rowsWritten,
        int percent,
        int attempts,
        boolean degraded,
        List<String> degradedStages,
        List<String> omittedSheets,
        List<String> formats,
        String failureCode,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * Projects a row, leaving out everything a caller has no use for.
     *
     * @param run      the row
     * @param basePath this module's mounted base path, so the result links in the envelope resolve
     *                 for the deployment the caller is actually talking to rather than for the
     *                 default one
     * @return the response
     */
    public static ReportRunResponse of(ExportReportRun run, String basePath) {
        List<String> degraded = run.getDegradedStages() == null ? List.of() : run.getDegradedStages();
        return new ReportRunResponse(run.getId(), run.getDefinitionKey(), run.getStatus(),
                envelope(run, basePath),
                run.getRowsWritten(), run.getPercent(), run.getAttempts(), !degraded.isEmpty(),
                degraded, run.getOmittedSheets() == null ? List.of() : run.getOmittedSheets(),
                run.getFormats(), run.getFailureCode(), run.getStartedAt(), run.getFinishedAt());
    }

    /**
     * The row as the platform envelope.
     *
     * <p>The result is the run's first format with the rest as alternatives, which is what makes the
     * contract's "a terminal success has exactly one answer to where is it" true for a run that was
     * asked for CSV and XLSX at once.
     */
    private static OperationResponse envelope(ExportReportRun run, String basePath) {
        return OperationResponse.builder()
                .id(run.getId().toString())
                .status(run.getStatus())
                .progress(new OperationProgress(run.getRowsWritten(), null, "rows", null))
                .submittedAt(run.getCreatedAt())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .result(result(run, basePath))
                .failure(failure(run))
                .correlationId(run.getCorrelationId())
                .build();
    }

    /**
     * Where the file is, once there is one.
     *
     * <p>Null before the run succeeds and null once retention has removed the outputs, which is why
     * {@code EXPIRED} is a terminal state that owes no result - see {@code OperationStatus}.
     */
    private static OperationResult result(ExportReportRun run, String basePath) {
        if (run.getStatus() != OperationStatus.SUCCEEDED || run.getFormats() == null
                || run.getFormats().isEmpty()) {
            return null;
        }
        String prefix = basePath + "/runs/" + run.getId() + "/outputs/";
        List<OperationResult> all = run.getFormats().stream()
                .map(format -> OperationResult.at(prefix + format))
                .toList();
        return all.get(0).withAlternatives(all.subList(1, all.size()));
    }

    /**
     * Why there is no file.
     *
     * <p>The message key and its arguments rather than a sentence, so a client that renders the code
     * itself produces the same text this service would - the row never stores a formatted sentence
     * for exactly that reason.
     */
    private static OperationFailure failure(ExportReportRun run) {
        if (run.getFailureCode() == null) {
            return null;
        }
        return OperationFailure.of(run.getFailureCode(),
                run.getFailureArguments() == null ? List.of() : run.getFailureArguments());
    }
}
