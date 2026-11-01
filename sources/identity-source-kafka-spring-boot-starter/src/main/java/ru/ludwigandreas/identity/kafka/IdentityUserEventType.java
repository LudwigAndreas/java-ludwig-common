package ru.ludwigandreas.identity.kafka;

/**
 * What a provider says happened to a user. There is no "role added" / "role removed" pair: an {@link #UPSERT}
 * carries the complete role set, so a lost or duplicated event cannot leave the projection out of step for good.
 */
public enum IdentityUserEventType {
    /** The user exists; the event carries their current state. */
    UPSERT,
    /** Deactivated at the provider. The row stays; the user resolves to no authorities. */
    DISABLE,
    /** Deleted at the provider. Projected as a status, never a row deletion. */
    DELETE
}
