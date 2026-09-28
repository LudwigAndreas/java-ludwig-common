package ru.ludwigandreas.webcore.operation;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * This kind of operation cannot be cancelled at all.
 *
 * <p>{@code 405 Method Not Allowed} rather than {@code 409}: the refusal is a property of the
 * resource and not of its current state, so it will be the same answer at every point in the
 * operation's life. A {@code 409} says "not right now" and invites the client to keep trying.
 *
 * <p>This is <em>not</em> the answer for cancelling an operation that has already finished. That is
 * not an error - see {@link OperationResponses#cancellationRequested}, which returns the terminal
 * envelope, because a {@code 409} there is a retry loop over a state that will never change.
 */
public class OperationNotCancellableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param operationId the operation somebody tried to cancel
     */
    public OperationNotCancellableException(String operationId) {
        super(ProblemStatus.METHOD_NOT_ALLOWED, ProblemCodes.OPERATION_NOT_CANCELLABLE, operationId);
        withProperty("operationId", operationId);
    }
}
