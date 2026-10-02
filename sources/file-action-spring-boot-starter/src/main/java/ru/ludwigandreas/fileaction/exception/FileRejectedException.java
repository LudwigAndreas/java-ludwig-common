package ru.ludwigandreas.fileaction.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The submitted file as a whole cannot be used.
 *
 * <h2>Why a file-level refusal is an exception and a row-level one is not</h2>
 *
 * <p>A {@code ProblemDetail} is the right shape for "this file is not acceptable": there is one reason,
 * the request did not happen, and {@code web-core}'s single RFC 9457 pipeline renders it in the caller's
 * locale with no work here. It is the wrong shape for "three thousand of your ten thousand rows are
 * wrong" - a 400 carrying three thousand entries is not a usable API, the client cannot page it, and the
 * user cannot fix a sheet from a JSON array. Those go to {@code file_action_row_reject} and to the
 * annotated workbook instead.
 *
 * <p>The division is worth stating because the tempting shortcut - one more {@code violations} list on
 * the problem - is how an import API becomes unusable at the exact size where it starts to matter.
 */
public class FileRejectedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * A refusal with the status the code implies.
     *
     * @param status the HTTP meaning
     * @param code   a constant from {@link FileActionProblemCodes}
     * @param args   message arguments, in the order the bundle expects them
     */
    public FileRejectedException(ProblemStatus status, String code, Object... args) {
        super(status, code, args);
    }

    /**
     * A refusal caused by something thrown underneath - a corrupt ZIP, a truncated stream.
     *
     * @param status the HTTP meaning
     * @param code   a constant from {@link FileActionProblemCodes}
     * @param cause  what went wrong
     * @param args   message arguments
     */
    public FileRejectedException(ProblemStatus status, String code, Throwable cause, Object... args) {
        super(status, code, cause, args);
    }
}
