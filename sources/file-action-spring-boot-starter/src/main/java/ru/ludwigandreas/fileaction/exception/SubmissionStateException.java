package ru.ludwigandreas.fileaction.exception;

import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The submission exists but is not in a state this request can act on - a confirm of something already
 * applied, say.
 *
 * <p>Deliberately not used for a cancel of a terminal submission: the operation contract says that
 * returns the envelope rather than a 409, because the caller's intent - "make sure this is not running"
 * - is already satisfied and an error would make them handle a success.
 */
public class SubmissionStateException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Names what was asked for and what state prevented it.
     *
     * @param code    a constant from {@link FileActionProblemCodes}
     * @param current the state the submission is actually in
     */
    public SubmissionStateException(String code, FileActionState current) {
        super(ProblemStatus.CONFLICT, code, current.name());
    }
}
