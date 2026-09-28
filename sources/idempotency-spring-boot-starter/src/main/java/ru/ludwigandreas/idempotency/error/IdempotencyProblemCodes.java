package ru.ludwigandreas.idempotency.error;

/**
 * The problem codes this module emits, and therefore part of the API contract of every endpoint it
 * protects.
 *
 * <p>Constants rather than literals because a code is published in the response body: a client is
 * expected to branch on {@code code} instead of parsing a translated {@code detail}. The keys in
 * {@code i18n/ludwig-idempotency-messages.properties} are exactly these values, plus a
 * {@code .title} sibling for each.
 */
public final class IdempotencyProblemCodes {

    /** Namespace prefix for every code below. */
    public static final String PREFIX = "ludwig.idempotency.error.";

    /**
     * The key is held by a request that is still running.
     *
     * <p>A 409 with {@code Retry-After}. Deliberately not the original response, which does not exist
     * yet, and deliberately not a second execution.
     */
    public static final String IN_PROGRESS = PREFIX + "in-progress";

    /**
     * The key was used before, for a different request.
     *
     * <p>A 422 naming the key. The one problem in this module that means the <em>client</em> is broken
     * rather than merely unlucky, and the one whose counter is worth alerting on.
     */
    public static final String FINGERPRINT_MISMATCH = PREFIX + "fingerprint-mismatch";

    /**
     * The work was done under this key and its response was not stored, so there is nothing to replay.
     *
     * <p>A 409, and a distinct code rather than reusing {@link #IN_PROGRESS}, because the two need opposite
     * things from the caller: an in-flight duplicate should come back shortly, and this one never should -
     * the work has happened, the answer is gone, and retrying will produce this same response until the
     * claim's window passes. Telling a client to retry here would produce a polite infinite loop.
     *
     * <p>Only reachable when a response was too large to store or fell outside the configured stored status
     * range. Both are deployment decisions, which is why the message names them.
     */
    public static final String NOT_REPLAYABLE = PREFIX + "not-replayable";

    /** The endpoint requires an {@code Idempotency-Key} and the request carried none. */
    public static final String KEY_REQUIRED = PREFIX + "key-required";

    /** The key is longer than the column that has to hold it. */
    public static final String KEY_TOO_LONG = PREFIX + "key-too-long";

    private IdempotencyProblemCodes() {
    }
}
