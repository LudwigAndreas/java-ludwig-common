package ru.ludwigandreas.export.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The report was produced and the sink would not take it.
 *
 * <p>A separate outcome from {@link ExportWriteException} because the operational response is
 * different: a write failure is about this instance's disk, and a sink failure is about the store
 * everything else also depends on. Conflating them would make the two indistinguishable in the one
 * place an operator looks first.
 *
 * <p>The run fails and the temp file is deleted. Keeping the file for a later upload was considered
 * and rejected: it would make the temp directory a queue with no bound, no owner and no eviction,
 * on the very instance whose storage is already the thing that went wrong.
 */
public class SinkUnavailableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public SinkUnavailableException(String formatId, Throwable cause) {
        super(ProblemStatus.SERVICE_UNAVAILABLE, ExportProblemCodes.SINK_UNAVAILABLE, cause, formatId);
        withProperty("format", formatId);
    }
}
