package ru.ludwigandreas.reconciliation.quota;

import ru.ludwigandreas.reconciliation.exception.ReconciliationException;

/**
 * A call could not get a permit from its partner-scoped rate limit within the configured timeout.
 *
 * <p>Retryable by construction: the permit will be there shortly, and the call that could not get one
 * is exactly the call that should back off. It surfaces as an ordinary fetch failure so that the
 * key's backoff and budget apply to it like any other transient problem.
 */
public class RateLimitExceededException extends ReconciliationException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which limit, and for how long it was waited on
     */
    public RateLimitExceededException(String message) {
        super(message);
    }
}
