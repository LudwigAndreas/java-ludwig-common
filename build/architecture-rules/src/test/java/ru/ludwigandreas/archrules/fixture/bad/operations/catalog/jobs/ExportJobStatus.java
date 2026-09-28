package ru.ludwigandreas.archrules.fixture.bad.operations.catalog.jobs;

/**
 * A sixth vocabulary, invented locally because this module needed somewhere to record where its runs
 * got to.
 *
 * <p>This is the shape the rule exists to catch, and it is worth noting how reasonable it looks. It
 * is a correct, well-named enum. The only thing wrong with it is that four other modules already
 * have one, and that one of them spells the third constant {@code SUCCEEDED}.
 */
public enum ExportJobStatus {

    /** Accepted, not started. */
    QUEUED,

    /** Started. */
    RUNNING,

    /** Finished, and it worked. */
    COMPLETED,

    /** Finished, and it did not. */
    FAILED,

    /** Somebody stopped it. */
    CANCELLED
}
