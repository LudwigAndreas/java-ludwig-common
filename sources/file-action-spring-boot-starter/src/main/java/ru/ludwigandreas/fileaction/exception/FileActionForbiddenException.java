package ru.ludwigandreas.fileaction.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The caller does not hold the authority the action declares.
 *
 * <p>A 403 rather than a 404. Hiding the action's existence would be defensible, but the action name is already
 * discoverable - it is the configuration key a deployment publishes, and the template endpoint serves its headings
 * - so a 404 here would conceal nothing and would tell an authorised user who mistyped nothing useful.
 *
 * <p>The message names the authority, because the person reading it is usually an administrator working out which
 * role to grant.
 */
public class FileActionForbiddenException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Names the action and the authority the caller lacks.
     *
     * @param action    the action from the path
     * @param authority the authority it requires
     */
    public FileActionForbiddenException(String action, String authority) {
        super(ProblemStatus.FORBIDDEN, FileActionProblemCodes.FORBIDDEN, action, authority);
    }
}
