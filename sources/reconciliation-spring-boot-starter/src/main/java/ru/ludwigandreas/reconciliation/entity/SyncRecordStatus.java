package ru.ludwigandreas.reconciliation.entity;

/**
 * Lifecycle of a staged external record.
 *
 * <p>Deliberately mirrors {@code OutboxStatus}'s shape - claimable states, one claimed state, and
 * terminal states - so that an operator who has debugged one of the two modules can read the other's
 * table without learning a second vocabulary. What differs is the set of terminal outcomes, because
 * applying external state has more ways of legitimately doing nothing than dispatching a message
 * does.
 */
public enum SyncRecordStatus {

    /** Fetched and written down; waiting for the apply pass to claim it. */
    STAGED,

    /** Claimed by one instance's apply pass. */
    PROCESSING,

    /** Applied: the local record changed. */
    APPLIED,

    /**
     * Applied in the sense that it was considered and correctly did nothing: the payload hash matched
     * what was already applied, or the reconciler found nothing to change. Terminal, and counted
     * separately from {@link #APPLIED} because "we polled 400,000 records and 12 changed" is the
     * number that tells an operator whether the sweep is worth its cost.
     */
    UNCHANGED,

    /** The reconciler refused it, permanently - a domain invariant, or state older than local. */
    REJECTED,

    /** The reconciler asked to be tried again later; not an error, and does not count as a failure. */
    DEFERRED,

    /** The partner does not know this key, and the task's not-found policy is {@code mark-missing}. */
    MISSING,

    /** An attempt failed; eligible for retry once {@code next_attempt_at} passes. */
    FAILED,

    /**
     * Retries exhausted. Terminal until an operator requeues it: the record stays, with its last
     * error, rather than being deleted or silently retried forever.
     */
    QUARANTINED
}
