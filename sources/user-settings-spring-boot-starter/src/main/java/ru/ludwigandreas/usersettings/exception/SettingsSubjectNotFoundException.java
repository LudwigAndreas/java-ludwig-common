package ru.ludwigandreas.usersettings.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * An administrator named a subject or scope of another tenant.
 *
 * <p>{@code 404} rather than {@link SettingsAccessDeniedException}'s {@code 403}: on the administrative surface a
 * resource of another tenant does not exist for the caller, and a {@code 403} would confirm that it does.
 */
public class SettingsSubjectNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public SettingsSubjectNotFoundException() {
        super(ProblemStatus.NOT_FOUND, SettingProblemCodes.SUBJECT_NOT_FOUND);
    }
}
