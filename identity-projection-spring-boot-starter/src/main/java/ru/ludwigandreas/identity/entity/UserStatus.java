package ru.ludwigandreas.identity.entity;

/**
 * Whether a projected user may still act.
 *
 * <p>Users are disabled rather than deleted when the directory deactivates them: a deleted row would
 * break every audit record and {@code created_by} reference pointing at that subject, and would make a
 * re-created account silently inherit the old one's history.
 */
public enum UserStatus {

    ACTIVE,

    /** Deactivated upstream. Authenticates, resolves to no authorities at all. */
    DISABLED
}
