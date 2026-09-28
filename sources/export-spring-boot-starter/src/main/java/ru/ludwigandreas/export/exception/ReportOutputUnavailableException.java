package ru.ludwigandreas.export.exception;

import java.util.UUID;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A download addressed a run that has no file to give.
 *
 * <p>Two situations, two codes, one class, because they differ only in which code a client branches
 * on: the run has not produced a file <em>yet</em> ({@code OUTPUT_NOT_READY}, and the caller should
 * poll the status), or it produced one and retention has since removed it
 * ({@code OUTPUT_EXPIRED}, and the caller should run the report again). Conflating them would send
 * somebody to poll for a file that is never coming back.
 *
 * <p>The status differs too - 409 against 410 - so a client that branches on status rather than on
 * code still gets the distinction.
 */
@Getter
public final class ReportOutputUnavailableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final transient UUID runId;

    private ReportOutputUnavailableException(ProblemStatus status, String code, UUID runId) {
        super(status, code, runId);
        this.runId = runId;
        withProperty("run", runId);
    }

    /** The run exists and has not produced this file yet. */
    public static ReportOutputUnavailableException notReady(UUID runId) {
        return new ReportOutputUnavailableException(ProblemStatus.CONFLICT,
                ExportProblemCodes.OUTPUT_NOT_READY, runId);
    }

    /** The file existed and the retention purge has removed it. */
    public static ReportOutputUnavailableException expired(UUID runId) {
        return new ReportOutputUnavailableException(ProblemStatus.GONE,
                ExportProblemCodes.OUTPUT_EXPIRED, runId);
    }
}
