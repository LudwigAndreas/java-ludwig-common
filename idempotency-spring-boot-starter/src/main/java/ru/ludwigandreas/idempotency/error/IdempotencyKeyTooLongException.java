package ru.ludwigandreas.idempotency.error;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The key is longer than the column that has to hold it.
 *
 * <p>Refused at the edge rather than truncated. A truncated key is the collision the scoping rules
 * exist to prevent, arriving from the opposite direction: two different long keys sharing a prefix
 * would become one claim, and the second caller's genuine request would be answered as a duplicate of
 * the first's. Refusing is loud, wrong-by-one-request, and fixable by the client; truncating is silent
 * and drops work.
 */
public class IdempotencyKeyTooLongException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param length the length the caller sent
     * @param limit  the longest key this platform stores
     */
    public IdempotencyKeyTooLongException(int length, int limit) {
        super(ProblemStatus.INVALID, IdempotencyProblemCodes.KEY_TOO_LONG, length, limit);
        withProperty("length", length);
        withProperty("maxLength", limit);
    }
}
