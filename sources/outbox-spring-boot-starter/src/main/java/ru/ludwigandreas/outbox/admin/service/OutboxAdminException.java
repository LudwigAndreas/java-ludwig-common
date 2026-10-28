package ru.ludwigandreas.outbox.admin.service;

import java.util.UUID;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why an administrative request about an outbox message could not be carried out.
 */
public final class OutboxAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private OutboxAdminException(ProblemStatus status, String code, Object... args) {
        super(status, code, args);
    }

    static OutboxAdminException notFound(UUID id) {
        return new OutboxAdminException(ProblemStatus.NOT_FOUND, "ludwig.outbox.admin.message-not-found", id);
    }

    static OutboxAdminException notRetryable(UUID id, OutboxStatus status) {
        return new OutboxAdminException(ProblemStatus.CONFLICT, "ludwig.outbox.admin.not-retryable", id, status)
                .withProperty("status", status.name());
    }

    static OutboxAdminException notDiscardable(UUID id, OutboxStatus status) {
        return new OutboxAdminException(ProblemStatus.CONFLICT, "ludwig.outbox.admin.not-discardable", id, status)
                .withProperty("status", status.name());
    }

    static OutboxAdminException bulkStatusRequired() {
        return new OutboxAdminException(ProblemStatus.INVALID, "ludwig.outbox.admin.bulk-status-required");
    }
}
