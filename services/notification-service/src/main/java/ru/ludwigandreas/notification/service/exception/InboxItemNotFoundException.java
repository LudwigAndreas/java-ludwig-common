package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * No inbox item with this id belongs to the caller. Rendered as 404.
 *
 * <p><b>One exception for two situations, deliberately.</b> This is raised both when the item does
 * not exist and when it exists and belongs to somebody else, and nothing in the response
 * distinguishes them - not the status, not the message, not a property.
 *
 * <p>Returning 403 for the second case would be the conventional answer and would be wrong here. A
 * 403 is itself information: it confirms that an item with that id exists, and repeated against
 * guessed ids it enumerates what the platform has told other people. That is worse than the usual
 * version of this trade-off, because an inbox item's existence is exactly the fact its owner would
 * consider private - "has this person been told about the disciplinary meeting" is answerable from a
 * status code alone.
 *
 * <p>The same reasoning is why the id is a message argument but not a machine-readable property: the
 * caller already knows the id they asked for, and echoing it adds nothing while making the two cases
 * easier to tell apart in a log.
 */
public class InboxItemNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public InboxItemNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.notification.inbox.not-found", id);
    }
}
