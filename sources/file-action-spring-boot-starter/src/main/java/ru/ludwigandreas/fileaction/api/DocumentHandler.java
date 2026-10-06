package ru.ludwigandreas.fileaction.api;

import java.util.stream.Stream;

/**
 * Applies every row as one business fact. The shape for "400 rows become one order with 400 lines".
 *
 * <p>A {@link DocumentHandler} is all-or-nothing by construction: there is one invocation, one
 * transaction and one outcome, so the action's commit policy has nothing to decide and configuring
 * anything other than {@code ALL_OR_NOTHING} for one is refused at startup.
 *
 * @param <R> the row record
 */
public non-sealed interface DocumentHandler<R> extends FileActionHandler<R> {

    /**
     * Applies the whole document.
     *
     * <h2>The stream is lazy and must be consumed once</h2>
     *
     * <p>It reads from the submission's bound rows as it is traversed, so the handler may fold it,
     * group it and count it, but must not collect it into a list "to make it easier" - that puts the
     * entire file in the heap and is the behaviour the module's whole reader exists to avoid. If the
     * logic genuinely needs two passes, say so by throwing: the honest answer is a size ceiling on the
     * action, not a hidden materialisation.
     *
     * <h2>A per-row rule must not be checked here, and the reason is structural</h2>
     *
     * <p>{@code ApplyPass} hands this method {@code rows.map(BoundRow::payload)}: the {@link RowAddress} is
     * stripped, and one invocation returns one {@link RowOutcome} carrying one code. So a rule evaluated per
     * row inside this method can report its <em>first</em> failure only, with no location - which is the
     * import error report the module's whole reject pipeline exists to avoid. A user handed "something was
     * wrong" for a file with thirty problems fixes it by trial and error.
     *
     * <p>Every rule whose subject is one row therefore belongs to the bind phase, whatever it costs to put it
     * there: a Bean Validation constraint on the row record, a class-level constraint, or the record's compact
     * constructor. Those run before anything is applied, every problem with every row is collected rather than
     * the first, and each becomes a {@link ru.ludwigandreas.fileaction.format.RowProblem} addressed at a sheet,
     * a displayed row and a column header. A rule needing a database lookup is still a per-row rule and still
     * belongs there - a {@code ConstraintValidator} is an injectable bean; keep it stateless, because it is a
     * singleton shared by every submission, and put a {@code CachePurpose.PERFORMANCE} cache behind the lookup
     * rather than issuing one query per row.
     *
     * <p>What is left for this method is the set of rules with no single row as their subject: the lines must
     * sum to a declared total, every line must name the same customer, no key may appear twice, the document
     * must have at least one line. For those, one unaddressed code is the honest answer rather than a
     * limitation. Put the offending values in the outcome's arguments - they are the only locating information
     * a document reject has.
     *
     * <h2>An action with this handler needs {@code reject-threshold: 0}</h2>
     *
     * <p>A row that fails to bind is never written to the bound-row artifact, so it never reaches this method.
     * The threshold decides whether such rows refuse the submission at all, and its default of {@code 0.1}
     * means a four-hundred-line document with thirty unbindable lines is applied as one business fact built
     * from the three hundred and seventy that survived - the "we created the first 38 lines of your order"
     * outcome the handler split exists to prevent. The threshold is orthogonal to the commit policy and cannot
     * tell which handler shape it is serving, so unlike the policy it is not refused at startup; it is written
     * down here, in the module's README, and in {@code docs/harness-enforcement.md}.
     *
     * <h2>This method must not call a partner service, and nothing can check that for you</h2>
     *
     * <p>It runs inside one transaction covering every row, which for a large document is a long
     * transaction. A partner call inside it holds a database connection for the partner's latency and,
     * when the partner is slow, exhausts the pool for the whole application - and if the transaction
     * then rolls back, the partner has still been called. Publish through
     * {@code outbox-spring-boot-starter} instead, so the call and the commit cannot disagree.
     *
     * <p>No bytecode rule can tell a partner call from any other method invocation, which is why this
     * is stated here and recorded in {@code docs/harness-enforcement.md} as deliberately unenforced.
     * For a document large enough that the transaction is a concern, configure the action
     * {@code DEFERRED} so the work is not also holding an HTTP request open.
     *
     * @param rows    the bound rows, lazily; consume once. Fold them <em>before</em> returning on
     *                {@link FileActionContext#dryRun()}: an early return skips the cross-row checks, and a
     *                {@code CONFIRM} action then shows a clean preview of a document that is refused on
     *                confirm
     * @param context what is known about the submission. When {@link FileActionContext#dryRun()} is
     *                true this call must change nothing
     * @return what happened; a non-applied outcome rolls the whole document back
     */
    RowOutcome apply(Stream<R> rows, FileActionContext context);
}
