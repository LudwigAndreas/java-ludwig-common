package ru.ludwigandreas.reconciliation.entity;

/** Whether the last attempt to apply a member, or to report it as having left, succeeded. */
public enum ScopeMemberStatus {

    /** Applied, or seen unchanged. */
    ACTIVE,

    /** The integration threw; retried with backoff the next time a walk lists the member. */
    FAILED,

    /**
     * The attempt budget is spent. Not handed to the integration again until an operator requeues it,
     * and its scope does not sweep while it stays here.
     */
    QUARANTINED
}
