package ru.ludwigandreas.reconciliation.api;

import java.time.Duration;

/**
 * What a {@link Reconciler} did with an external record.
 *
 * <p>Four outcomes rather than a boolean, because the engine's accounting, metrics and retry
 * behaviour differ for each, and the distinctions are the ones an operator asks about: "did anything
 * actually change?", "is this record broken or just not ready?".
 */
public sealed interface ReconcileResult {

    /**
     * The local record was updated.
     *
     * @param detail short description for the audit trail, for example which fields changed
     */
    record Applied(String detail) implements ReconcileResult {
    }

    /**
     * The external state matched what the local record already reflected, so nothing was written.
     *
     * <p>A first-class outcome rather than an {@code Applied} that happened to change nothing. It
     * means no write, no {@code updated_at} churn, no audit row and no downstream event - which in a
     * system that polls a large catalogue on a schedule is the difference between a quiet database
     * and one whose replication lag is dominated by rewriting rows to their existing values.
     */
    record Unchanged() implements ReconcileResult {
    }

    /**
     * The external record is not acceptable and never will be: it fails a domain invariant, it is
     * older than what the local record already reflects, or it refers to something this service does
     * not have. Terminal - no retry, no backoff.
     *
     * @param reason why it was rejected, recorded on the staged row and in the audit trail
     */
    record Rejected(String reason) implements ReconcileResult {
    }

    /**
     * The record cannot be applied yet, but should be retried - a dependency has not arrived, a
     * related aggregate is locked, a prerequisite sync has not run.
     *
     * <p>Distinct from a failure: it consumes a retry attempt but is not an error, so it does not
     * count toward the failure metric an alert is wired to, and the delay is the reconciler's own
     * rather than the task's backoff curve.
     *
     * @param retryAfter how long to wait before the next attempt
     * @param reason     what is being waited for
     */
    record Deferred(Duration retryAfter, String reason) implements ReconcileResult {
    }

    /** The local record was updated. */
    static ReconcileResult applied(String detail) {
        return new Applied(detail);
    }

    /** Nothing needed to change. */
    static ReconcileResult unchanged() {
        return new Unchanged();
    }

    /** The record is not acceptable and will not become acceptable. */
    static ReconcileResult rejected(String reason) {
        return new Rejected(reason);
    }

    /** Not yet; try again after {@code retryAfter}. */
    static ReconcileResult deferred(Duration retryAfter, String reason) {
        return new Deferred(retryAfter, reason);
    }
}
