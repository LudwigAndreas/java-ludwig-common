package ru.ludwigandreas.fileaction.api;

/**
 * Applies one row at a time. The shape for "400 rows become 400 orders".
 *
 * <p>Because the module invokes this per row, a row that cannot be applied is <em>one</em> row: it
 * becomes a reject and the other three hundred and ninety-nine are applied, subject to the action's
 * commit policy. That isolation is the whole reason this shape exists and is why the module, not the
 * handler, owns the batching and the transaction boundary.
 *
 * @param <R> the row record
 */
public non-sealed interface RowHandler<R> extends FileActionHandler<R> {

    /**
     * Applies one row.
     *
     * <h2>This method must be idempotent per row, and nothing can check that for you</h2>
     *
     * <p>A {@code DEFERRED} submission is claimed under a lease. If the pod dies after the transaction
     * carrying rows 501-1000 committed but before the submission's progress was recorded, another
     * instance resumes and <em>re-applies those rows</em>. A handler that inserts unconditionally
     * creates five hundred duplicate orders, once, in production, on the day a node is drained.
     *
     * <p>Make the write conditional on something derived from the row - a natural key, an external
     * reference, the submission id plus the row number - so that applying it twice is applying it once.
     * No bytecode or source-text analysis can see whether a write is conditional, which is why this is
     * stated here, at the seam, and recorded in {@code docs/harness-enforcement.md} as deliberately
     * unenforced rather than left to be discovered.
     *
     * <h2>What this method must not do</h2>
     *
     * <p>It runs inside the transaction the module opened for the batch. It must not open a transaction
     * of its own, and anything it does that a rollback cannot undo - sending a mail, calling a partner,
     * writing a file - will have happened for rows whose transaction then rolled back. Publish those
     * through {@code outbox-spring-boot-starter} so the side effect and the row commit together.
     *
     * @param row     the bound row
     * @param context what is known about the submission. When {@link FileActionContext#dryRun()} is
     *                true this call must change nothing and exists only to report whether it could
     * @return what happened to the row; see {@link RowOutcome} for why a bad row is returned rather
     *         than thrown
     */
    RowOutcome apply(R row, FileActionContext context);
}
