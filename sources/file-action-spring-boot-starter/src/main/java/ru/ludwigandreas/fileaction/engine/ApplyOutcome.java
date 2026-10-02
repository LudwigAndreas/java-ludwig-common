package ru.ludwigandreas.fileaction.engine;

import ru.ludwigandreas.fileaction.api.RowOutcome;

/**
 * What the apply pass did.
 *
 * @param rowsApplied how many rows the handler applied and the transaction kept
 * @param cancelled   whether it stopped because somebody asked it to
 * @param failedBatch the 1-based batch that rolled back under {@code PER_BATCH}, or null
 * @param documentCode the reason a {@code DocumentHandler} refused the document, or null
 */
public record ApplyOutcome(long rowsApplied, boolean cancelled, Integer failedBatch,
                           String documentCode) {

    /** A document that applied. */
    static ApplyOutcome documentApplied() {
        // One, not the row count: a DocumentHandler applied one business fact, and reporting the row count as
        // "applied" would tell a client four hundred orders were created when one was.
        return new ApplyOutcome(1, false, null, null);
    }

    /**
     * A document the handler refused.
     *
     * @param outcome what the handler said, which may be null if it returned nothing
     * @return the outcome
     */
    static ApplyOutcome documentRejected(RowOutcome outcome) {
        return new ApplyOutcome(0, false, null, outcome == null ? "unknown" : outcome.code());
    }

    /** An {@code ALL_OR_NOTHING} run that rolled back over a refused row. */
    static ApplyOutcome allOrNothingRejected() {
        return new ApplyOutcome(0, false, null, null);
    }

    /** Whether anything was applied. */
    public boolean appliedAnything() {
        return rowsApplied > 0;
    }

    /** Whether a batch rolled back. */
    public boolean hadFailedBatch() {
        return failedBatch != null;
    }
}
