package ru.ludwigandreas.idempotency.entity;

/**
 * Where a claim is in its life.
 *
 * <p>Stored as a string rather than an ordinal, for the reason every enum column in this platform is:
 * an ordinal makes inserting a constant in the middle of the enum a data migration, and makes the
 * table unreadable to anyone who does not have the Java source to hand - and an operator looking at
 * this table is usually doing so during an incident, without it.
 *
 * <p>{@link #COMPLETED} is the only state a {@link ru.ludwigandreas.idempotency.api.ClaimMode#TRANSACTIONAL}
 * claim is ever in. That mode's claim commits with the work, so a row that is visible at all describes
 * work that committed: there is no moment at which another transaction can see an unfinished
 * transactional claim, which is precisely what makes it need no lease.
 */
public enum ClaimState {

    /**
     * The holder is working. Only reachable in
     * {@link ru.ludwigandreas.idempotency.api.ClaimMode#STANDALONE}.
     *
     * <p>The only state that carries a lease, and the only one a reclaim can take from a live row. A
     * row left here by a process that died is the failure the lease exists for: without it the key
     * would be reserved forever for work nobody is doing, and the caller could never retry.
     */
    IN_PROGRESS,

    /** The work was done. A duplicate is answered from here - with the stored response, if there is one. */
    COMPLETED,

    /**
     * The work failed and the key is free again.
     *
     * <p>Immediately reclaimable rather than held to the end of the TTL. A failed attempt must not
     * block the retry it exists to enable - that would turn one transient failure into a window during
     * which the operation cannot be performed at all.
     *
     * <p>The row is kept rather than deleted so that an operator asking "why is this caller retrying?"
     * has the reason to read, and so that the reclaim has exactly one shape instead of an insert branch
     * for a key nobody holds and an update branch for one whose holder failed.
     */
    FAILED
}
