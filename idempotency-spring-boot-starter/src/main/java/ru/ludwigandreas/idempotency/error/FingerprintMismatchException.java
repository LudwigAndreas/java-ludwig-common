package ru.ludwigandreas.idempotency.error;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The key has been used before, for a different request.
 *
 * <p>422 rather than 409, and the distinction is the diagnosis. A 409 says "the current state of the
 * resource conflicts with your request", which invites a retry; this is not retryable and retrying
 * will produce the same answer forever. The request was well-formed and addressed a valid resource,
 * and its <em>content</em> cannot be acted on because the key it carries already belongs to a
 * different ask - which is exactly what {@link ProblemStatus#UNPROCESSABLE} means.
 *
 * <p>What it must not do is return the first request's response. A client that recycles keys would
 * then receive somebody else's resource with a 200, which is a data-integrity failure that presents
 * as "the API returned the wrong data" and has no trace anywhere. Refusing loudly turns a silent
 * correctness bug into a bug report.
 *
 * <p>The fingerprints themselves are never in the problem document. They are hashes of request bodies,
 * and publishing one would let a caller confirm a guess about another request's contents; the key is
 * named because the client sent it, and the trace id is how this is tied to the log line that has the
 * rest.
 */
public class FingerprintMismatchException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param scope the scope the key was claimed in
     * @param key   the key that was reused
     */
    public FingerprintMismatchException(String scope, String key) {
        super(ProblemStatus.UNPROCESSABLE, IdempotencyProblemCodes.FINGERPRINT_MISMATCH, key);
        withProperty("idempotencyKey", key);
        withProperty("scope", scope);
    }
}
