package ru.ludwigandreas.reconciliation.api;

/**
 * Which half of a task's demand a run is asking for.
 *
 * <p>Splitting demand is what lets one integration have two honest cadences. The records that matter
 * - the ones in a non-terminal status, or that changed in the last few minutes - are a small set that
 * can be polled every thirty seconds; the full catalogue is a large set that can only be swept every
 * few hours. A single cadence has to choose, and whichever it chooses is wrong for the other half:
 * fast means hammering the partner with a sweep, slow means an order sitting in "awaiting payment"
 * for four hours after it was paid.
 */
public enum DemandTier {

    /** Records that need external state soon: non-terminal status, or recently changed. */
    HOT,

    /** The full sweep, run rarely, that catches anything the hot query's definition missed. */
    COLD
}
