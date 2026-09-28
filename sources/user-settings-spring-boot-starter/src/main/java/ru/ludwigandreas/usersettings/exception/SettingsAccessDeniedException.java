package ru.ludwigandreas.usersettings.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The caller asked for someone else's settings without holding the administrative authority that
 * allows it, or asked across a tenant boundary.
 *
 * <p>Enforced inside this module rather than left to each caller. The rule "a user may read and
 * write only their own settings" is the kind that is correct in every service and therefore gets
 * reimplemented in each of them, slightly differently, until one of them forgets - and the one that
 * forgets is an endpoint that takes a subject id from the path and passes it straight through.
 *
 * <p>Deliberately says nothing about whose settings were asked for. The message a denied caller gets
 * back must not confirm that a subject exists.
 */
public class SettingsAccessDeniedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public SettingsAccessDeniedException() {
        super(ProblemStatus.FORBIDDEN, SettingProblemCodes.ACCESS_DENIED);
    }
}
