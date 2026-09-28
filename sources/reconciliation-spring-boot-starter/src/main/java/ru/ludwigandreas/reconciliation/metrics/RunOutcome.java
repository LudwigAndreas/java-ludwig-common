package ru.ludwigandreas.reconciliation.metrics;

/**
 * How a run ended, as a metric tag.
 */
public enum RunOutcome {

    /** The run completed. */
    SUCCESS,

    /** The run threw. Records it had already staged are unaffected - that is what staging is for. */
    FAILURE,

    /** Another instance held the run lock. Not a failure, and counted separately so it never looks like one. */
    SKIPPED_LOCKED,

    /** The run hit its {@code run-timeout} and stopped early; whatever it had staged still applies. */
    TIMED_OUT
}
