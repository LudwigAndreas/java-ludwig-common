package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The announcement named an audience this deployment does not permit. Rendered as 422.
 *
 * <p><b>One message for two situations, deliberately.</b> This is raised both when the audience kind
 * is not in {@code allowed-audiences} and when a role is not in {@code targetable-roles}, and the
 * response does not say which - nor, for a role, whether that role exists.
 *
 * <p>That is not vagueness for its own sake. A free-form role target is an enumeration primitive: an
 * announcer who could tell "no such role" from "that role exists but you may not target it" could
 * discover the organisation's whole role structure by publishing to guesses. The same reasoning as
 * the inbox's indistinguishable 404, applied to a different surface.
 *
 * <p>The operator-facing answer is in the logs and in the configuration, where it belongs: the
 * deployment's own {@code announcements} block says exactly what is permitted.
 */
public class AudienceNotPermittedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public AudienceNotPermittedException() {
        super(ProblemStatus.UNPROCESSABLE, "error.notification.announcement.audience-not-permitted");
    }
}
