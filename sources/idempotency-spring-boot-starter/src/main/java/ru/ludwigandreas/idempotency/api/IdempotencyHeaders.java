package ru.ludwigandreas.idempotency.api;

/**
 * The headers this platform speaks on both sides of a call.
 *
 * <h2>The outbound half, which is the reason this class is in the published api package</h2>
 *
 * <p>Two mechanisms belong to work-dedup, and only one of them is a store. The other is sending a key
 * <em>downstream</em> so the far side can dedup, which is what makes a {@code POST} retryable at all:
 * {@code rest-client-spring-boot-starter}'s retry policy refuses to repeat a non-idempotent method
 * unless the caller opts in, and the honest basis for opting in is that the request carries one of
 * these. A retry of a {@code POST} with no dedup key is a second order, not a second attempt.
 *
 * <p>{@code crud-service-example}'s {@code ProductNotificationDispatcher} is the worked example, and
 * the rule it states is the one that matters: the key must be <b>stable across every attempt</b>. It
 * forwards the outbox row's own key, falling back to the row id - both of which are generated once,
 * at publish time, and reused by every dispatch of that row. What must never be used is anything
 * generated per attempt; a fresh key on each retry makes the far side's deduplication useless in
 * exactly the case it exists for, while looking correct in every test that retries once.
 */
public final class IdempotencyHeaders {

    /**
     * The request header carrying the caller's dedup key.
     *
     * <p>{@code Idempotency-Key} is the conventional spelling, and it is a header rather than a body
     * field so that it is visible to a proxy and cannot be confused with the payload it protects. The
     * one case where a key belongs in the body is a batch endpoint, which carries one key per item -
     * a header cannot carry one value per item, and the store is directly callable for exactly that
     * reason.
     */
    public static final String KEY = "Idempotency-Key";

    /**
     * Marks a response that was replayed from a stored claim rather than produced by the handler.
     *
     * <p>Present only on a replay, and never on the original. A client does not need it to be
     * correct - that is the point of a replay being byte-identical - but an operator reading an access
     * log or a caller debugging a retry storm does, and without it a replayed {@code 201} is
     * indistinguishable from a second resource having been created.
     */
    public static final String REPLAYED = "Idempotency-Replayed";

    /**
     * Echoes the key a response was served under.
     *
     * <p>On both the original and the replay, so that a client correlating responses to keys does not
     * have to keep its own map through a retry it may have made from a different thread.
     */
    public static final String ECHO = "Idempotency-Key-Echo";

    private IdempotencyHeaders() {
    }
}
