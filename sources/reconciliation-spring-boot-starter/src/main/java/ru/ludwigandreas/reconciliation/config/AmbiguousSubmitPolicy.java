package ru.ludwigandreas.reconciliation.config;

/**
 * What to do about a job row stuck in {@code PENDING_SUBMIT} past its grace period - meaning the
 * request may or may not have reached the partner, and the process that made it is gone.
 *
 * <p>The state is <b>ambiguous, not lost</b>, and the two readings have opposite costs. There is no
 * safe default across partners, which is why this setting is required for any task using
 * {@code shape: async-job} and why the context refuses to start without it.
 *
 * <p>Neither branch applies when the partner implements {@code listActive()}: the engine matches on
 * the committed idempotency key and adopts the handle, which answers the question instead of guessing
 * at it.
 */
public enum AmbiguousSubmitPolicy {

    /**
     * Assume the job is running. The row is marked {@code ORPHANED}, its quota slot is held until
     * {@code max-lifetime}, and nothing is resubmitted.
     *
     * <p>Correct for anything expensive or billable. The cost of being wrong is one wasted slot for
     * the lifetime window and demand that syncs late; the cost of the other reading being wrong is a
     * duplicate remote job that the partner runs, charges for, and may not deduplicate.
     */
    ASSUME_SUBMITTED,

    /**
     * Assume the request never arrived and submit again.
     *
     * <p>Correct only when a submission is cheap and genuinely idempotent on the partner's side -
     * typically when they honour the idempotency key. Anywhere else this is how one crash turns into
     * two running jobs.
     */
    RESUBMIT
}
