package ru.ludwigandreas.ingest.engine;

import ru.ludwigandreas.ingest.api.IngestRunSummary;
import ru.ludwigandreas.ingest.exception.BalanceFailedException;

/**
 * The arithmetic a run must satisfy before it is allowed to be {@code COMPLETED}.
 *
 * <h2>Why this is the module's correctness proof and not a sanity check</h2>
 *
 * <p>Every claim this module makes about not losing data is a claim about one equation:
 *
 * <pre>
 *   records_read == records_applied + records_quarantined + records_skipped
 * </pre>
 *
 * <p>Every record the parser produced was either written to staging, set aside with a reason, or
 * deliberately collapsed by the applier. If the four numbers disagree, at least one record went
 * somewhere nobody can name - and "somewhere nobody can name" is exactly the data loss the module
 * exists to prevent. A module that merely logged the discrepancy would be a module that loses data
 * and says so in a line nobody reads.
 *
 * <p>So it gates the status. A run that cannot balance is {@code FAILED}, loudly, with all four
 * numbers in the message, and the target is left untouched because the merge has not run. The
 * difference between a module that claims not to lose records and one that demonstrates it every
 * single run is this method being called before the status is written rather than after.
 *
 * <h2>The sentinel's count is the stronger check, when there is one</h2>
 *
 * <p>The equation above reconciles the run against itself. It is satisfied perfectly by a run that
 * read a truncated file: everything it read was accounted for, and what was missing was never read.
 * A sentinel declaring how many records the file should hold is the only thing in the module capable
 * of catching that, because it is the only number that comes from outside the run.
 */
public final class BalanceCheck {

    private BalanceCheck() {
    }

    /**
     * Verifies the run, throwing if it does not balance.
     *
     * @param summary         the run's counts
     * @param expectedRecords what the sentinel declared, or {@code null} when it declared nothing
     * @throws BalanceFailedException if the counts do not reconcile, or disagree with the sentinel
     */
    public static void verify(IngestRunSummary summary, Long expectedRecords) {
        if (!summary.balances()) {
            throw new BalanceFailedException(summary);
        }
        if (expectedRecords != null && summary.recordsRead() != expectedRecords) {
            throw new BalanceFailedException(summary, expectedRecords);
        }
    }

    /**
     * Verifies that what is actually in staging matches what the run believes it wrote.
     *
     * <p>A separate check from the equation above, and a stronger one, because it compares the
     * engine's own tally against a {@code COUNT(*)} of the table. The equation reconciles the engine's
     * numbers with each other and would be satisfied by a run whose staging writer silently dropped
     * rows - a batch that reported five thousand written and wrote four thousand nine hundred. Only
     * counting the table catches that.
     *
     * <p>Skipped when the applier collapses duplicates: a run with {@code recordsSkipped > 0} has
     * deliberately staged fewer rows than it applied, and this check has no way to know how many
     * fewer. That is stated rather than worked around, because guessing would make the check report
     * failures on correct runs, and a check that cries wolf gets switched off.
     *
     * @param summary the run's counts
     * @param staged  what a {@code COUNT(*)} of the staging table returned
     * @throws BalanceFailedException if they disagree
     */
    public static void verifyStaged(IngestRunSummary summary, long staged) {
        if (summary.recordsSkipped() > 0) {
            return;
        }
        if (staged != summary.recordsApplied()) {
            throw new BalanceFailedException(new IngestRunSummary(summary.runId(), summary.task(),
                    summary.sourceUri(), summary.contentIdentity(), summary.bytesRead(),
                    summary.recordsRead(), staged, summary.recordsQuarantined(),
                    summary.recordsSkipped(), summary.startedAt(), summary.finishedAt()));
        }
    }
}
