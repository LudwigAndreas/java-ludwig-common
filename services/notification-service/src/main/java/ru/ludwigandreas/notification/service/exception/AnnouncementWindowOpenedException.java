package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A window change tried to move the start of an announcement that is already showing. Rendered as
 * 409.
 *
 * <p>Recipients have already seen it, so the start is a fact rather than a plan; only the end can
 * still move.
 */
public class AnnouncementWindowOpenedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public AnnouncementWindowOpenedException(UUID id) {
        super(ProblemStatus.CONFLICT, "error.notification.announcement.window-opened", id);
    }
}
