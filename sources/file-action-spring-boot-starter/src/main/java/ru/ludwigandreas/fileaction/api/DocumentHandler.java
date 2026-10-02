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
     * @param rows    the bound rows, lazily; consume once
     * @param context what is known about the submission. When {@link FileActionContext#dryRun()} is
     *                true this call must change nothing
     * @return what happened; a non-applied outcome rolls the whole document back
     */
    RowOutcome apply(Stream<R> rows, FileActionContext context);
}
