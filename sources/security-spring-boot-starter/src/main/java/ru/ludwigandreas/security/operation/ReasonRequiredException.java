package ru.ludwigandreas.security.operation;

import java.util.Map;
import ru.ludwigandreas.security.exception.SecurityRefusalException;

/**
 * A call to a privileged operation that did not state why it was made, or stated it at unreasonable length.
 *
 * <p>Raised only after the caller has been found to hold the operation, so that the requirement does not tell a
 * caller who may not perform the operation anything about it. Its code differs from a body validation failure so a
 * client can prompt for the reason rather than for a corrected form.
 */
public final class ReasonRequiredException extends SecurityRefusalException {

    private static final long serialVersionUID = 1L;

    /** The problem code. */
    public static final String CODE = "ludwig.security.reason-required";

    /**
     * Creates the exception.
     */
    public ReasonRequiredException() {
        super(CODE, Map.of(), OperationEvaluator.REASON_HEADER, OperationEvaluator.MAX_REASON_LENGTH);
    }
}
