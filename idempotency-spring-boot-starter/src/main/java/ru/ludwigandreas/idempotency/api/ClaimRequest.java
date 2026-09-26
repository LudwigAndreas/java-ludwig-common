package ru.ludwigandreas.idempotency.api;

import java.time.Duration;
import java.util.UUID;

/**
 * Everything a claim needs, as one value rather than seven positional arguments.
 *
 * <p>A record rather than a long parameter list because five of the seven components are strings,
 * durations or ids that the compiler would happily let a caller transpose - and a transposed scope
 * and key is a bug that dedups nothing and looks like it works.
 *
 * @param mode        whether the claim commits with the caller's work or on its own; see
 *                    {@link ClaimMode}. Required, with no default, on purpose
 * @param scope       what the key is unique within. Scoped rather than global because a Kafka record
 *                    key and an HTTP {@code Idempotency-Key} come from different namespaces, and a
 *                    collision between them silently drops a genuine request. See
 *                    {@link IdempotencyScopes}
 * @param key         the caller's own dedup key, verbatim
 * @param requestId   the id that owns the claim if this call wins it. Supplied by the caller rather
 *                    than generated here, so the winner can record the same id on the work itself and
 *                    a duplicate can be handed the id of the thing that was actually done
 * @param fingerprint a hash of the request this key was used for, or {@code null} to store none. When
 *                    a duplicate presents a different fingerprint the claim is refused rather than
 *                    answered - see {@link RequestFingerprint}
 * @param ttl         how long the claim stays good for. A correctness parameter, not housekeeping: it
 *                    is the window in which a retry is recognised, so shortening it converts
 *                    duplicates into double executions rather than into disk savings
 * @param lease       how long an {@link ClaimOutcome#IN_PROGRESS} claim is good for without a
 *                    renewal. Ignored in {@link ClaimMode#TRANSACTIONAL}, which has no in-progress
 *                    state to lease
 */
public record ClaimRequest(
        ClaimMode mode,
        String scope,
        String key,
        UUID requestId,
        String fingerprint,
        Duration ttl,
        Duration lease) {

    /** Rejects a request that could not identify a claim, and a TTL or lease that is not a window. */
    public ClaimRequest {
        if (mode == null) {
            throw new IllegalArgumentException("A claim must name its ClaimMode; there is no default");
        }
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("A claim needs a scope");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A claim needs a key");
        }
        if (requestId == null) {
            throw new IllegalArgumentException("A claim needs the request id that would own it");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("A claim's ttl must be positive, was " + ttl);
        }
        if (mode == ClaimMode.STANDALONE && (lease == null || lease.isZero() || lease.isNegative())) {
            throw new IllegalArgumentException(
                    "A STANDALONE claim needs a positive lease so a dead holder's key is reclaimable, was "
                            + lease);
        }
    }

    /** A transactional claim, which has no lease. */
    public static ClaimRequest transactional(String scope, String key, UUID requestId, Duration ttl) {
        return new ClaimRequest(ClaimMode.TRANSACTIONAL, scope, key, requestId, null, ttl, null);
    }

    /** A standalone claim, leased. */
    public static ClaimRequest standalone(String scope, String key, UUID requestId, Duration ttl,
                                          Duration lease) {
        return new ClaimRequest(ClaimMode.STANDALONE, scope, key, requestId, null, ttl, lease);
    }

    /** This request with a fingerprint attached. */
    public ClaimRequest withFingerprint(String value) {
        return new ClaimRequest(mode, scope, key, requestId, value, ttl, lease);
    }
}
