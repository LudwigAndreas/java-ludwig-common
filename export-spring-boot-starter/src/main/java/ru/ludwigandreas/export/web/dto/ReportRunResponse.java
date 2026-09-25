package ru.ludwigandreas.export.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.export.api.RunStatus;
import ru.ludwigandreas.export.entity.ExportReportRun;

/**
 * What a caller sees about a run.
 *
 * <p>Carries no parameters and no principal snapshot. Those are on the row for the audit trail, and
 * echoing them back would put a requester's own parameters - which may be PII - into a response that
 * a proxy will log. What a caller needs in order to act is the status, the progress and, when it
 * failed, the code; all three are here.
 *
 * @param id             the run
 * @param definitionKey  which report
 * @param status         where it is in its life
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
        RunStatus status,
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

    /** Projects a row, leaving out everything a caller has no use for. */
    public static ReportRunResponse of(ExportReportRun run) {
        List<String> degraded = run.getDegradedStages() == null ? List.of() : run.getDegradedStages();
        return new ReportRunResponse(run.getId(), run.getDefinitionKey(), run.getStatus(),
                run.getRowsWritten(), run.getPercent(), run.getAttempts(), !degraded.isEmpty(),
                degraded, run.getOmittedSheets() == null ? List.of() : run.getOmittedSheets(),
                run.getFormats(), run.getFailureCode(), run.getStartedAt(), run.getFinishedAt());
    }
}
