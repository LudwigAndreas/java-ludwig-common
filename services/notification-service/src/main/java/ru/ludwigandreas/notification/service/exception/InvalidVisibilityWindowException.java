package ru.ludwigandreas.notification.service.exception;

import java.time.Instant;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A window change put the end, or a not-yet-open start, in the past. Rendered as 422.
 *
 * <p>An end in the past would be a withdrawal without its record - who, when and why - so taking an
 * announcement down now is the withdrawal endpoint's job, not this one's.
 */
public class InvalidVisibilityWindowException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public InvalidVisibilityWindowException(Instant from, Instant until) {
        super(ProblemStatus.UNPROCESSABLE, "error.notification.announcement.window-in-past",
                from, until);
    }
}
