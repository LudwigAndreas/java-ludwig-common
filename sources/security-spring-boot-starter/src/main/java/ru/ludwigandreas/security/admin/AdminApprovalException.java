package ru.ludwigandreas.security.admin;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why a four-eyes approval could not be recorded or decided.
 */
public final class AdminApprovalException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private static final String PREFIX = "ludwig.admin.approval.";

    private AdminApprovalException(ProblemStatus status, String code, Object... args) {
        super(status, PREFIX + code, args);
    }

    static AdminApprovalException notFound(Object id) {
        return new AdminApprovalException(ProblemStatus.NOT_FOUND, "not-found", id);
    }

    static AdminApprovalException ownRequest() {
        return new AdminApprovalException(ProblemStatus.FORBIDDEN, "own-request");
    }

    static AdminApprovalException notRequester() {
        return new AdminApprovalException(ProblemStatus.FORBIDDEN, "not-requester");
    }

    static AdminApprovalException notPending(Object id, AdminApprovalState state) {
        return new AdminApprovalException(ProblemStatus.CONFLICT, "not-pending", id, state);
    }

    static AdminApprovalException requesterLostOperation(String requester, String operation) {
        return new AdminApprovalException(ProblemStatus.CONFLICT, "requester-lost-operation", requester, operation);
    }

    static AdminApprovalException bodyTooLarge(int limit) {
        return new AdminApprovalException(ProblemStatus.PAYLOAD_TOO_LARGE, "body-too-large", limit);
    }
}
