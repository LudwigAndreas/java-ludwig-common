package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The announcement named a category this deployment has not configured. Rendered as 422.
 *
 * <p>Rejected rather than defaulted, and the reason is what a default would decide on the announcer's
 * behalf: a category carries both a declinability and a channel set, so falling back to "some
 * sensible category" could silently make an announcement declinable when it was meant to be
 * mandatory, or send a hundred thousand emails when none were wanted.
 *
 * <p>The category name is echoed because the caller supplied it and a typo is the common case. The
 * list of valid categories is deliberately <em>not</em> echoed: it is the deployment's configuration,
 * and an announcer who may publish is not automatically somebody who should be handed the catalogue.
 */
public class UnknownAnnouncementCategoryException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public UnknownAnnouncementCategoryException(String category) {
        super(ProblemStatus.UNPROCESSABLE, "error.notification.announcement.unknown-category",
                category);
        withProperty("category", category);
    }
}
