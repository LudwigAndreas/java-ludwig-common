package ru.ludwigandreas.identity.kafka;

/**
 * What the directory says happened to a user.
 *
 * <p>Note that there is no "role added" / "role removed" pair. The event carries the user's complete role
 * set and the projection replaces what it holds, so a lost or duplicated event cannot leave the
 * projection permanently out of step with the directory the way an incremental delta would. Replaying the
 * topic from the beginning converges to the correct state; replaying deltas would not.
 */
public enum OidcUserEventType {

    /** The user exists; the event carries their current state in full. */
    UPSERT,

    /** Deactivated upstream. The row stays, its status changes - see {@code UserStatus}. */
    DISABLE,

    /**
     * Removed upstream. Also projected as a status change rather than a delete, so audit records and
     * {@code created_by} references that point at this subject keep resolving.
     */
    DELETE
}
