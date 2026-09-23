package ru.ludwigandreas.reconciliation.api;

import java.time.Duration;
import java.util.Optional;

/**
 * What a partner says about an asynchronous job the engine submitted.
 *
 * <p>Four states, not a boolean and not an HTTP status, because the engine reacts differently to each
 * and collapsing any two of them causes a specific failure: treating {@code EXPIRED} as
 * {@code FAILED} retries work the partner has already thrown away, and treating {@code FAILED} as
 * {@code RUNNING} polls a dead job until its lifetime runs out while its quota slot stays occupied.
 */
public sealed interface JobStatus {

    /**
     * Still working.
     *
     * @param retryAfter the partner's own guidance on when to ask again - an HTTP {@code Retry-After},
     *                   a documented completion estimate - or null to let the engine's adaptive
     *                   backoff decide. Honouring it is what keeps a polite integration polite
     */
    record Running(Duration retryAfter) implements JobStatus {

        /** The partner's polling guidance, if it gave any. */
        public Optional<Duration> retryAfterOrEmpty() {
            return Optional.ofNullable(retryAfter);
        }
    }

    /** Finished; the result is ready to collect. */
    record Succeeded() implements JobStatus {
    }

    /**
     * Finished unsuccessfully and will not produce a result.
     *
     * @param reason what the partner said, recorded on the job row and in the audit trail
     */
    record Failed(String reason) implements JobStatus {
    }

    /**
     * The partner no longer knows about this job - its result window elapsed, or it was garbage
     * collected on their side. Distinct from {@link Failed} because there is nothing to report to a
     * human about the work itself: the demand it covered simply has to be requeued.
     */
    record Expired() implements JobStatus {
    }

    /** Still working, with no guidance on when to ask again. */
    static JobStatus running() {
        return new Running(null);
    }

    /** Still working; ask again no sooner than {@code retryAfter}. */
    static JobStatus running(Duration retryAfter) {
        return new Running(retryAfter);
    }

    /** Finished successfully. */
    static JobStatus succeeded() {
        return new Succeeded();
    }

    /** Finished unsuccessfully. */
    static JobStatus failed(String reason) {
        return new Failed(reason);
    }

    /** No longer known to the partner. */
    static JobStatus expired() {
        return new Expired();
    }
}
