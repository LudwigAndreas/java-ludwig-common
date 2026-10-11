package ru.ludwigandreas.reconciliation.engine.enumeration;

import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;

/**
 * What one walk of one scope did in one run.
 *
 * @param scopeKey the encoded scope key
 * @param status   how the walk ended
 * @param kind     the kind of walk; null when the scope was removed before one started
 * @param members  what was done with the members listed
 * @param sweep    what the sweep after a completed full walk did; null when none ran
 * @param error    why the walk failed; null otherwise
 */
public record WalkReport(String scopeKey,
                         Status status,
                         ScopeWalkKind kind,
                         PageOutcome members,
                         SweepService.Result sweep,
                         String error) {

    /** How a walk ended. */
    public enum Status {

        /** Reached the last page. */
        COMPLETED,

        /** Ran out of the run's time; resumes from its checkpoint. */
        INTERRUPTED,

        /** A page could not be fetched or applied; resumes from the same page after a backoff. */
        FAILED,

        /** The scope's local row is gone; its state and membership were forgotten. */
        REMOVED
    }

    /** The status as a metric tag. */
    public String statusTag() {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }
}
