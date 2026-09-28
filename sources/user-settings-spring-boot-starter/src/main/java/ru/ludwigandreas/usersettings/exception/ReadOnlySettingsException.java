package ru.ludwigandreas.usersettings.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A write reached a service running the module in projection mode.
 *
 * <p>A projection is a replica of another service's data. Accepting the write locally would produce
 * a value that the owner does not have, that the next projected event silently overwrites, and that
 * the user saw take effect in between - which is worse than refusing, because it looks like it
 * worked.
 */
public class ReadOnlySettingsException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public ReadOnlySettingsException() {
        super(ProblemStatus.CONFLICT, SettingProblemCodes.READ_ONLY);
    }
}
