package ru.ludwigandreas.fileaction.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/** No submission with that id under that action. */
public class SubmissionNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Names the submission that is not there.
     *
     * @param action the action it was looked for under
     * @param id     the id asked for
     */
    public SubmissionNotFoundException(String action, Object id) {
        super(ProblemStatus.NOT_FOUND, FileActionProblemCodes.NOT_FOUND, action, id);
    }
}
