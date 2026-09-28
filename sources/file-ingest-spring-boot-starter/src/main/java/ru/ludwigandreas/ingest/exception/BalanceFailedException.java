package ru.ludwigandreas.ingest.exception;

import ru.ludwigandreas.ingest.api.IngestRunSummary;

/**
 * The counts did not add up, so the run is failed rather than completed.
 *
 * <h2>Why this is an exception and not a warning</h2>
 *
 * <p>{@code records_read == records_applied + records_quarantined + records_skipped} is the module's
 * correctness proof, and a proof that can be ignored is not one. Every record the parser produced was
 * either written, quarantined, or deliberately dropped by the applier; if the four numbers disagree
 * then at least one record went somewhere nobody can name, and "somewhere nobody can name" is the
 * definition of the data loss this module exists to prevent.
 *
 * <p>The failure is loud and carries the numbers, because the numbers are the whole diagnosis: a
 * shortfall in {@code applied} points at the applier, a shortfall in the total points at the parser,
 * and an excess points at a batch counted twice. A message saying only "balance check failed" would
 * make somebody re-derive all of that from logs.
 *
 * <p>The run keeps its counts and its checkpoint. They are the evidence.
 */
public class BalanceFailedException extends IngestException {

    private static final long serialVersionUID = 1L;

    /**
     * A run whose numbers do not reconcile.
     *
     * @param summary the run's counts at the moment the check ran
     */
    public BalanceFailedException(IngestRunSummary summary) {
        super("Ingest run " + summary.runId() + " for task '" + summary.task() + "' does not balance:"
                + " read=" + summary.recordsRead()
                + " applied=" + summary.recordsApplied()
                + " quarantined=" + summary.recordsQuarantined()
                + " skipped=" + summary.recordsSkipped()
                + " (applied+quarantined+skipped="
                + (summary.recordsApplied() + summary.recordsQuarantined() + summary.recordsSkipped())
                + "). The run is FAILED and the target is unchanged.");
    }

    /**
     * A run that read a different number of records from the one its sentinel declared.
     *
     * @param summary  the run's counts
     * @param expected the count the sentinel declared
     */
    public BalanceFailedException(IngestRunSummary summary, long expected) {
        super("Ingest run " + summary.runId() + " for task '" + summary.task() + "' read "
                + summary.recordsRead() + " records but its sentinel declared " + expected
                + ". The run is FAILED and the target is unchanged: a file that is shorter than its own"
                + " manifest is the signature of a truncated upload.");
    }
}
