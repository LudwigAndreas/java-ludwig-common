package ru.ludwigandreas.reconciliation.engine.enumeration;

import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;

import java.time.Instant;

/**
 * Decides what kind of walk a scope gets next, and when it is next due.
 *
 * <p>Pure functions of a scope's state and the task's settings, kept apart from the code that runs
 * the walk so that the rules can be read - and tested - without a database or a partner.
 */
public final class WalkPlanner {

    private WalkPlanner() {
    }

    /**
     * The kind of walk a scope should get now.
     *
     * <p>A walk already in progress resumes as what it started as. Otherwise the walk is full unless
     * every condition for an incremental one holds: incremental walks are configured, a full walk has
     * completed, that walk left a watermark to ask for changes since, and the full interval has not
     * elapsed. A scope with no watermark has nothing to bound a delta with, so asking for one would
     * be a full listing that the engine believed was partial.
     *
     * @param state    the scope's state
     * @param settings the task's settings
     * @param now      the current instant
     * @return the kind
     */
    public static ScopeWalkKind kindFor(SyncScopeState state, EnumerationSettings settings, Instant now) {
        if (state.getWalkKind() != null) {
            return state.getWalkKind();
        }
        if (!canWalkIncrementally(state, settings)) {
            return ScopeWalkKind.FULL;
        }
        return now.isBefore(state.getLastFullAt().plus(settings.fullInterval()))
                ? ScopeWalkKind.INCREMENTAL : ScopeWalkKind.FULL;
    }

    /**
     * When a scope that has just completed a walk is next due.
     *
     * @param state    the scope's state, already carrying the completed walk's timestamps
     * @param settings the task's settings
     * @return the earlier of the next full and the next incremental walk
     */
    public static Instant nextDue(SyncScopeState state, EnumerationSettings settings) {
        Instant fullDue = state.getLastFullAt().plus(settings.fullInterval());
        if (!canWalkIncrementally(state, settings)) {
            return fullDue;
        }
        Instant lastListed = state.getLastIncrementalAt() == null
                ? state.getLastFullAt() : state.getLastIncrementalAt();
        Instant incrementalDue = lastListed.plus(settings.incrementalInterval());
        return incrementalDue.isBefore(fullDue) ? incrementalDue : fullDue;
    }

    private static boolean canWalkIncrementally(SyncScopeState state, EnumerationSettings settings) {
        return settings.isIncremental() && state.getLastFullAt() != null && state.getWatermark() != null;
    }
}
