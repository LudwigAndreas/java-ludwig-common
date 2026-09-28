package ru.ludwigandreas.idempotency.error;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The work behind this key was done, and its response was not kept.
 *
 * <p>The honest answer to a duplicate whose original response was too large to store, or carried a status
 * outside the configured stored range. A 200 with an empty body would claim an answer this module does not
 * have; re-running the handler would be the double execution the claim exists to prevent.
 *
 * <p>Deliberately <em>not</em> a {@link ClaimInProgressException} with a {@code Retry-After}. The two look
 * alike - both are a 409 about a key somebody else holds - and they need opposite things from the caller: an
 * in-flight duplicate should come back shortly, and this one never should, because retrying will produce
 * this same response until the claim's window passes. A {@code Retry-After} here would produce a polite
 * infinite loop.
 */
public class ClaimNotReplayableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param key the key whose original response was not stored
     */
    public ClaimNotReplayableException(String key) {
        super(ProblemStatus.CONFLICT, IdempotencyProblemCodes.NOT_REPLAYABLE, key);
        withProperty("idempotencyKey", key);
    }
}
