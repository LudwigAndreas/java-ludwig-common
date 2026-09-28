package ru.ludwigandreas.archrules.fixture.bad.operations.catalog.jobs;

/**
 * An internal work record that happens to have the same three words, and is not an operation
 * vocabulary.
 *
 * <p>The reason the rule's third clause exists. Nobody cancels a claim on an idempotency key,
 * nothing expires one from a client's point of view, and it is never published - so although this
 * spans "not finished, worked, did not work" exactly as an operation status does, it is not one. A
 * rule without that clause would flag this, and a rule that has to be suppressed inside the platform
 * that introduced it does not survive a quarter.
 */
public enum ClaimState {

    /** The holder is working. */
    IN_PROGRESS,

    /** The work was done. */
    COMPLETED,

    /** The work failed and the key is free again. */
    FAILED
}
