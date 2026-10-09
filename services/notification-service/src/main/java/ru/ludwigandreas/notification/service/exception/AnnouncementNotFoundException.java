package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * No announcement with this id is available to the caller. Rendered as 404.
 *
 * <p>Raised both when the announcement does not exist and when it is not visible to this caller, and
 * the response does not distinguish them - the same reasoning as {@link InboxItemNotFoundException}.
 * Here the stake is slightly different: an announcement's existence is not private, but <em>which
 * roles the platform addresses</em> is, and a caller who could tell "no such announcement" from "not
 * for you" could map the audience structure by walking identifiers.
 */
public class AnnouncementNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public AnnouncementNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.notification.announcement.not-found", id);
    }
}
