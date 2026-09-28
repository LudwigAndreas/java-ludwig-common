package ru.ludwigandreas.export.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The file could not be written: the disk filled, the temp directory became unwritable, a writer
 * failed.
 *
 * <p>The underlying {@code IOException} is kept as the cause for the log and is never rendered into
 * the response. A message from a filesystem or a compression library is written for whoever operates
 * this service, not for whoever asked for a spreadsheet, and a path in it would leak the layout of
 * the container.
 *
 * <p>The partial file is already gone by the time this is thrown: the engine deletes it on every
 * exit path, so there is no half-written report for a retry to trip over and none for the sink to
 * be handed by mistake.
 */
public class ExportWriteException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public ExportWriteException(String formatId, Throwable cause) {
        super(ProblemStatus.INTERNAL, ExportProblemCodes.WRITE_FAILED, cause, formatId);
        withProperty("format", formatId);
    }
}
