package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A change was asked of an announcement that has been withdrawn. Rendered as 409.
 *
 * <p>Withdrawal is final: editing a withdrawn announcement would change a record nobody can see,
 * and restoring one is a new publish, so the history stays one line.
 */
public class AnnouncementWithdrawnException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public AnnouncementWithdrawnException(UUID id) {
        super(ProblemStatus.CONFLICT, "error.notification.announcement.withdrawn", id);
    }
}
