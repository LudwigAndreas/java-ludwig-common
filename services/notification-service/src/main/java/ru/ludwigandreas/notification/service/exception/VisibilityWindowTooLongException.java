package ru.ludwigandreas.notification.service.exception;

import java.time.Duration;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The announcement asked to be visible for longer than this deployment permits, or for no time at
 * all. Rendered as 422.
 *
 * <p>Both numbers are published as machine-readable properties, as {@code BatchTooLargeException}
 * does, so a client can correct itself rather than by reading prose.
 *
 * <p>Capped for two reasons. A very long window is a banner nobody removes - it stops being read and
 * starts being furniture. And retention is measured from the end of visibility, so an unbounded
 * window is a row that is never purged, which is why
 * {@code NotificationConfigurationValidator} also refuses a retention window shorter than this cap.
 */
public class VisibilityWindowTooLongException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public VisibilityWindowTooLongException(Duration requested, Duration permitted) {
        super(ProblemStatus.UNPROCESSABLE, "error.notification.announcement.window-too-long",
                requested, permitted);
        withProperty("requestedWindow", requested.toString());
        withProperty("permittedWindow", permitted.toString());
    }
}
