package ru.ludwigandreas.fileaction.api;

/**
 * The code half of one file action: what the bound rows are for.
 *
 * <h2>Sealed on two shapes, and the sealing is the point</h2>
 *
 * <pre>
 *   RowHandler&lt;R&gt;        400 rows become 400 orders        partial success is meaningful
 *   DocumentHandler&lt;R&gt;   400 rows become 1 order           partial success is meaningless
 * </pre>
 *
 * <p>{@code file-ingest}'s {@code RecordApplier} is per-record only, deliberately, so that one bad
 * record is one quarantine row rather than a failed batch of five thousand. That is the right contract
 * for a staging import and the wrong one here, because an Excel sheet whose rows are the <em>lines of
 * one order</em> is a single business fact, and "we created the first 38 lines of your order" is not an
 * outcome anybody wants. A module that offered only the per-row shape would have every author of an
 * aggregate import fold the rows up by hand inside a per-row callback, keeping mutable state across
 * invocations the module is free to batch, retry and parallelise.
 *
 * <p>So there are two shapes, and exactly two. The consequence that makes the distinction load-bearing
 * rather than cosmetic: <strong>the configured commit policy applies only to {@link RowHandler}.</strong>
 * A {@link DocumentHandler} is all-or-nothing by construction, and an action that configures
 * {@code PER_ROW} or {@code PER_BATCH} for one is refused at startup rather than quietly given a policy
 * it cannot honour.
 *
 * <p>There is no third shape, for the reason {@code reconciliation}'s {@code Fetcher} is sealed: a
 * third would have to be either a renaming of one of these or an admission that the module does not know
 * what partial success means for it, and both are worse than the author choosing.
 *
 * @param <R> the row record a {@link RowBinding} produces
 */
public sealed interface FileActionHandler<R> permits RowHandler, DocumentHandler {

    /**
     * How this action's file is bound into rows.
     *
     * <p>On the handler rather than in configuration because the binding is code and the handler is the
     * only thing that knows what shape of row it can apply. The pairing cannot be got wrong: there is
     * one of each, and they are declared together.
     *
     * @return the binding; never null
     */
    RowBinding<R> binding();
}
