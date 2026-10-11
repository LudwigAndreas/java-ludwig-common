package ru.ludwigandreas.reconciliation.api;

/**
 * What a {@link MemberReconciler} did about a member that left a scope.
 *
 * <p>The engine acts on neither value - it has already decided to forget the membership. The
 * distinction exists so that "the integration did nothing about it" is counted rather than silent.
 */
public enum LeftResult {

    /** The integration acted: a soft delete, a hard delete, an unlink. */
    HANDLED,

    /** The integration left its own row as it was. */
    KEPT
}
