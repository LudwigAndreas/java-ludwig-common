package ru.ludwigandreas.idempotency.metrics;

/**
 * What this module reports about itself.
 *
 * <h2>The one worth alerting on</h2>
 *
 * <p>{@link #fingerprintMismatch(String)}. Every other counter here describes traffic - duplicates and
 * replays are the normal, healthy consequence of retries, and a deployment with none of them is more
 * likely to have the feature switched off than to have perfect clients. A fingerprint mismatch means a
 * client is reusing one key for different requests, which is a bug in that client, and it is a bug
 * nobody will report: from the client's side it looks like an occasional 422, and from this side it
 * looks like nothing at all unless somebody is counting.
 *
 * <h2>Tag cardinality is part of the contract</h2>
 *
 * <p>The scope is the only tag, and it is drawn from a set fixed at deploy time - one per endpoint, one
 * per topic. What is deliberately never a tag is the key itself, the request id, or the caller: all
 * three are unbounded, and any one of them would turn a metrics backend into a per-request time series
 * store and take the cardinality budget of the whole estate with it. The interesting question about a
 * duplicate is usually "who sent it", and that question belongs to the audit trail.
 *
 * <p>Implementations must not throw. An outage in the metrics backend that stopped claims being taken
 * would be a strictly worse outage than the one it was reporting on.
 */
public interface IdempotencyMetrics {

    /** Does nothing, for a consumer with no Micrometer on its classpath. */
    IdempotencyMetrics NOOP = new IdempotencyMetrics() { };

    /**
     * A claim was attempted.
     *
     * @param scope   the scope
     * @param outcome the {@code ClaimOutcome} name - won, in progress, or already completed
     */
    default void claim(String scope, String outcome) {
    }

    /**
     * A stored response was replayed to a duplicate instead of the handler running again.
     *
     * @param scope the scope
     */
    default void replayed(String scope) {
    }

    /**
     * A key was presented with a different request from the one it was claimed for.
     *
     * @param scope the scope
     */
    default void fingerprintMismatch(String scope) {
    }

    /**
     * A claim was reported failed, freeing its key for the retry.
     *
     * @param scope the scope
     */
    default void failed(String scope) {
    }

    /**
     * The purge dropped claims past their window.
     *
     * @param purged how many rows were dropped in this run
     */
    default void purged(long purged) {
    }
}
