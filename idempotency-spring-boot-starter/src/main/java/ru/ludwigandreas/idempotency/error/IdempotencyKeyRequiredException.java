package ru.ludwigandreas.idempotency.error;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.idempotency.api.IdempotencyHeaders;

/**
 * The endpoint requires a dedup key and the request carried none.
 *
 * <p>400, because the request is malformed against this endpoint's contract in the same way a missing
 * required header always is. Raised only where the deployment or the handler asked for it - see
 * {@code Idempotent#required()} - because forcing a key on a caller who genuinely accepts a duplicate
 * only produces random keys that protect nothing.
 */
public class IdempotencyKeyRequiredException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public IdempotencyKeyRequiredException() {
        super(ProblemStatus.INVALID, IdempotencyProblemCodes.KEY_REQUIRED, IdempotencyHeaders.KEY);
        withProperty("header", IdempotencyHeaders.KEY);
    }
}
