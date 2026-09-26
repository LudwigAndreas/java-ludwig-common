package ru.ludwigandreas.idempotency.api;

/**
 * What happened when a key was claimed.
 *
 * <p>Three outcomes rather than a boolean, because a duplicate has two genuinely different answers
 * and conflating them is how an API ends up replaying a response that does not exist yet.
 */
public enum ClaimOutcome {

    /**
     * This caller owns the key and must do the work.
     *
     * <p>Also the outcome when a previous claim was reclaimed - its window had passed, its holder's
     * lease had expired, or it had been reported {@code FAILED}. From the winner's point of view
     * those are the same situation as a key nobody had ever claimed, and distinguishing them here
     * would only give every caller a branch with nothing different to do in it.
     */
    CLAIMED,

    /**
     * Somebody else holds the key and their work is still running.
     *
     * <p>Only reachable in {@link ClaimMode#STANDALONE}. The answer is a 409 with {@code Retry-After}
     * and never the original response, which does not exist yet - and never a second execution.
     */
    IN_PROGRESS,

    /**
     * The work has already been done under this key.
     *
     * <p>In {@link ClaimMode#STANDALONE} the stored response travels with this outcome and is
     * replayed verbatim. In {@link ClaimMode#TRANSACTIONAL} there is no stored response and what
     * travels is the owning request's id, which is what the original caller was told too.
     */
    COMPLETED;

    /** Whether this caller won the claim and must do the work. */
    public boolean won() {
        return this == CLAIMED;
    }
}
