package ru.ludwigandreas.fileaction.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The path named an action that is not configured.
 *
 * <p>A 404 rather than a 400: the action is a path segment, so an unknown one is an unknown resource.
 */
public class UnknownActionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Names the action that is not configured.
     *
     * @param action the name from the path
     */
    public UnknownActionException(String action) {
        super(ProblemStatus.NOT_FOUND, FileActionProblemCodes.UNKNOWN_ACTION, action);
    }
}
