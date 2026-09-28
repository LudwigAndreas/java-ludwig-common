package ru.ludwigandreas.export.exception;

import java.util.UUID;
import lombok.Getter;

/**
 * A run stopped because it was cancelled.
 *
 * <p>Not a {@code LocalizedException}: cancellation is not an error the requester needs explained,
 * it is the outcome they asked for. It travels as an exception only because that is how the engine
 * unwinds out of the middle of a stream with its stream closed, its writers closed and its temp
 * files deleted - the same unwinding every other failure gets, which is precisely why cancellation
 * is not special-cased into a return value that every layer would have to remember to check.
 */
@Getter
public class ReportCancelledException extends ExportException {

    private static final long serialVersionUID = 1L;

    private final transient UUID runId;
    private final long rowsWritten;

    /**
     * Reports a cancelled run.
     *
     * @param runId       the run
     * @param rowsWritten how far it got, for the run record and the audit trail
     */
    public ReportCancelledException(UUID runId, long rowsWritten) {
        super("Report run " + runId + " was cancelled after " + rowsWritten + " rows");
        this.runId = runId;
        this.rowsWritten = rowsWritten;
    }
}
