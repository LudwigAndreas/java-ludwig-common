package ru.ludwigandreas.messaging.db.admin.service;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why an administrative request about a database-log consumer could not be carried out.
 */
public final class MessagingAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private MessagingAdminException(ProblemStatus status, String code, Object... args) {
        super(status, code, args);
    }

    static MessagingAdminException consumerNotFound(String groupId, String topic) {
        return new MessagingAdminException(ProblemStatus.NOT_FOUND, "ludwig.messaging-db.admin.consumer-not-found",
                groupId, topic);
    }

    static MessagingAdminException notPaused(String groupId, String topic) {
        return new MessagingAdminException(ProblemStatus.CONFLICT, "ludwig.messaging-db.admin.not-paused", groupId,
                topic);
    }

    static MessagingAdminException invalidTarget(String reason) {
        return new MessagingAdminException(ProblemStatus.INVALID, "ludwig.messaging-db.admin.invalid-target", reason);
    }
}
