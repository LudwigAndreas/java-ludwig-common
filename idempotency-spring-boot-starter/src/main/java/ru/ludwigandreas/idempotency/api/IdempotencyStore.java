package ru.ludwigandreas.idempotency.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims a caller-supplied key in a scope, so the same ask cannot be acted on twice.
 *
 * <p>The platform's one work-dedup primitive. It was written in {@code notification-service}, where
 * the Kafka consumer is at-least-once by construction and REST callers retry on timeouts that were
 * often successful writes - so "have I already done this?" is not an edge case, it is the normal path
 * during any rebalance, any deploy and any network blip.
 *
 * <h2>What this is not</h2>
 *
 * <p>Deliberately narrow, and the boundary matters more than the implementation. This answers
 * <em>"have I done this work?"</em> about a key somebody handed us. It is not:
 *
 * <ul>
 *   <li><b>lookup by a unique column on a row that already exists</b> - the outbox's
 *       {@code findByIdempotencyKey} and the export module's run lookup. The key belongs to that row;
 *       a second table would add a write and a failure mode for nothing, and returning the existing
 *       row <em>is</em> those modules' contract;</li>
 *   <li><b>natural-key dedup</b> - file-ingest's unique constraint on {@code (bucket, key, etag)}.
 *       Nobody supplies a key there; identity is derived from the data, which is strictly stronger
 *       than trusting a caller to send the same string twice;</li>
 *   <li><b>"may I retry this method"</b> - RFC 9110's idempotent-method table, in
 *       {@code IdempotentMethods} and {@code HttpMethod#isIdempotent}. A different question with a
 *       different answer: a {@code GET} is idempotent and has no work to dedup, and a {@code POST}
 *       carrying one of these keys is retryable precisely <em>because</em> it carries one;</li>
 *   <li><b>last-write-wins convergence</b> - the identity projection's timestamp compare, the
 *       user-settings replay. Value-based convergence needs no key and no store.</li>
 * </ul>
 *
 * <p>Four of those are the correct local answer and a generic store would make each one worse: an
 * extra table, an extra write in the hot path, and a second thing that can fail. See this module's
 * README, "What this module deliberately does not absorb".
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <p>{@link #claim} has to be atomic against concurrent callers in <em>different processes</em>, not
 * merely different threads. A read-then-write implementation satisfies this interface and fails the
 * requirement, and it fails it silently - under low load it looks perfect. The enforcement belongs in
 * the storage engine: a unique constraint, or a single-statement compare-and-set.
 *
 * <p>An implementation need not support both {@link ClaimMode}s, and says which it supports through
 * {@link #supports(ClaimMode)}. A store asked for a mode it cannot serve must throw rather than
 * degrade to the other one, because both directions of that substitution are a correctness bug:
 * serving {@code TRANSACTIONAL} as {@code STANDALONE} leaves keys claimed for work that rolled back,
 * and serving {@code STANDALONE} as {@code TRANSACTIONAL} needs a transaction the caller does not
 * have.
 */
public interface IdempotencyStore {

    /**
     * Claims {@code request}'s key, or reports what the current holder is doing.
     *
     * @param request the claim
     * @return the outcome; never null
     */
    ClaimResult claim(ClaimRequest request);

    /**
     * The live claim on a key, for a read-only pre-check.
     *
     * <p>A pre-check and nothing more. Deciding whether to act on the answer is a read-then-write and
     * has exactly the race {@link #claim} exists to avoid - this is for an operator endpoint, a
     * diagnostic, or a cheap early exit before work that {@code claim} will redo the decision for
     * anyway.
     *
     * @param scope the scope
     * @param key   the key
     * @return the claim, or empty when the key is unclaimed or its window has passed
     */
    Optional<ClaimResult> find(String scope, String key);

    /**
     * Records that the work behind a {@link ClaimMode#STANDALONE} claim succeeded, with the response
     * to replay.
     *
     * <p>Committed independently of the caller. A claim left {@code IN_PROGRESS} because this was
     * never called is recovered by its lease expiring, which is the whole reason the lease exists.
     *
     * @param scope     the scope
     * @param key       the key
     * @param requestId the id that owns the claim. A completion for a claim somebody else now holds -
     *                  because this holder's lease expired while it was working - is ignored rather
     *                  than applied: the response it is offering describes work another instance is
     *                  in the middle of repeating
     * @param response  the response to replay, or {@code null} to complete the claim with nothing to
     *                  replay
     * @return whether the claim was still this caller's and was completed
     */
    boolean complete(String scope, String key, UUID requestId, StoredResponse response);

    /**
     * Records that the work behind a {@link ClaimMode#STANDALONE} claim failed, freeing the key.
     *
     * <p>Freeing it immediately rather than at the end of the TTL is the point: a failed attempt must
     * not block the retry it exists to enable. The row is kept rather than deleted so that the failure
     * is visible to an operator asking why a caller is retrying, and so that the reclaim has one
     * shape.
     *
     * @param scope     the scope
     * @param key       the key
     * @param requestId the id that owns the claim
     * @param reason    why it failed, for the operator reading the table. Never a payload
     * @return whether the claim was still this caller's and was marked failed
     */
    boolean fail(String scope, String key, UUID requestId, String reason);

    /**
     * Extends an {@link ClaimMode#STANDALONE} claim's lease.
     *
     * <p>Same contract as {@code RunLockHandle#renew}, and the same consequence: holding a claim is
     * never proof that you still hold it. A caller whose renewal returns {@code false} has been
     * superseded and must stop, because another instance is already redoing this work.
     *
     * @param scope     the scope
     * @param key       the key
     * @param requestId the id that owns the claim
     * @param lease     the new time-to-live
     * @return whether the claim is still this caller's
     */
    boolean renewLease(String scope, String key, UUID requestId, Duration lease);

    /**
     * Drops claims past their retention window.
     *
     * <p>Must run on exactly one replica - it is one of the jobs {@code job-core}'s leased lock exists
     * for. This module ships that job rather than leaving it to every consumer; see
     * {@code IdempotencyPurgeJob}.
     *
     * @param now the instant to compare each claim's window against
     * @return how many claims were released
     */
    long purgeExpired(Instant now);

    /**
     * Whether this store can serve {@code mode}.
     *
     * @param mode the mode
     * @return whether it is supported
     */
    boolean supports(ClaimMode mode);
}
