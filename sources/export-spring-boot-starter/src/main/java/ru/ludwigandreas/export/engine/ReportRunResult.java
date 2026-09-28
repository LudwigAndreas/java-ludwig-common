package ru.ludwigandreas.export.engine;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * What one successful pass over the rows produced.
 *
 * <p>Carries what the run record and the audit trail need and nothing else - in particular, no rows
 * and no file contents. A result object that held the output would undo the entire point of
 * streaming it to disk.
 *
 * @param runId          the run
 * @param rowsWritten    how many rows reached at least one file
 * @param elapsed        how long the pass took, measured from the injected clock
 * @param outputs        the stored files, one per requested format
 * @param omittedSheets  sheets that were left out of at least one output under
 *                       {@code MultiSheetStrategy.PRIMARY_ONLY}. Reported rather than silently
 *                       dropped, because a partial file that looks whole is the failure this module
 *                       is most concerned with
 * @param degradedStages enrichment stages that failed and were degraded rather than failing the run
 */
public record ReportRunResult(
        UUID runId,
        long rowsWritten,
        Duration elapsed,
        List<ReportOutput> outputs,
        List<String> omittedSheets,
        List<String> degradedStages) {

    public ReportRunResult {
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        omittedSheets = omittedSheets == null ? List.of() : List.copyOf(omittedSheets);
        degradedStages = degradedStages == null ? List.of() : List.copyOf(degradedStages);
    }

    /** Whether any enrichment stage was degraded, which every download of this run has to report. */
    public boolean isDegraded() {
        return !degradedStages.isEmpty();
    }
}
