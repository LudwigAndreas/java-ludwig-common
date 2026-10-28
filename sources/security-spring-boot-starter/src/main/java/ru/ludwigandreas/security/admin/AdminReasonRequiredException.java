package ru.ludwigandreas.security.admin;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A privileged administrative request that did not state why it was made, or stated it at unreasonable length.
 *
 * <p>Raised only after the caller has been found to hold the operation, so that the requirement does not tell a
 * caller who may not perform the operation anything about it. Its code differs from a body validation failure so a
 * client can prompt for the reason rather than for a corrected form.
 */
public final class AdminReasonRequiredException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /** The problem code. */
    public static final String CODE = "ludwig.admin.reason-required";

    /**
     * Creates the exception.
     */
    public AdminReasonRequiredException() {
        super(ProblemStatus.INVALID, CODE, AdminInvocation.REASON_HEADER, AdminInvocation.MAX_REASON_LENGTH);
    }
}
