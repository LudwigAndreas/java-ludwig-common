package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.RowOutcome;
import ru.ludwigandreas.fileaction.format.RowProblem;

/**
 * Applies the bound rows, with the transaction boundary the action's commit policy dictates.
 *
 * <h2>The commit policy is a transaction boundary and nothing else</h2>
 *
 * <p>That is the whole of the difference between the three, and saying it this plainly is worth doing because
 * the policies are otherwise easy to mistake for error-handling strategies:
 *
 * <pre>
 *   ALL_OR_NOTHING   one transaction over every row     a reject rolls all of it back
 *   PER_BATCH        one transaction per batch          a reject rolls that batch back
 *   PER_ROW          one transaction per batch          a reject is recorded; the batch commits
 * </pre>
 *
 * <p>{@code PER_BATCH} and {@code PER_ROW} have the same boundary and differ only in what a rejected row does
 * to it. That is not a redundancy: with independent rows, a batch that rolls back over one of them loses four
 * hundred and ninety-nine good rows, and with dependent ones, committing around a reject leaves the batch
 * half-applied. Which of those is acceptable is the domain's answer, and it is the reason the policy has no
 * default.
 *
 * <h2>A handler that throws is not a rejected row</h2>
 *
 * <p>{@link RowOutcome} is how a handler says a row is unacceptable, and it leaves the transaction intact. A
 * handler that <em>throws</em> has said something else - the database is gone, a partner is unreachable - and
 * the transaction is already doomed: continuing to call into it for the rest of the batch would produce a
 * cascade of secondary failures that hide the first one. So a throw ends the batch, whatever the policy.
 *
 * <h2>Cancellation is checked between batches</h2>
 *
 * <p>Not within one. A cancel that could stop a transaction half way would be a cancel that leaves the data in
 * a state the commit policy promised it would not be in - which is worse than taking one more batch to stop.
 * This is also why the cancel endpoint answers 202: the stop is requested here and achieved a batch later.
 */
public class ApplyPass {

    private final BoundRowStore boundRows;
    private final TransactionTemplate transactions;

    /**
     * Prepares the pass.
     *
     * @param boundRows    how bound rows are read back
     * @param transactions the template every transaction boundary below is opened with. Programmatic rather
     *                     than {@code @Transactional}, because the boundary is the thing that varies per
     *                     action and an annotation's is fixed at compile time
     */
    public ApplyPass(BoundRowStore boundRows, TransactionTemplate transactions) {
        this.boundRows = boundRows;
        this.transactions = transactions;
    }

    /** Asked between batches whether somebody has requested a stop. */
    @FunctionalInterface
    public interface CancellationCheck {

        /** Whether the submission should stop. */
        boolean isCancelled();
    }

    /**
     * Applies the rows of one submission.
     *
     * @param action    the resolved action
     * @param bound     the local bound-row artifact
     * @param context   what the handler is told about the submission
     * @param rejects   where rejects and skips are counted
     * @param cancelled asked between batches
     * @param <R>       the row type
     * @return what happened
     */
    public <R> ApplyOutcome apply(ResolvedAction<R> action, Path bound, FileActionContext context,
                                  RejectCollector rejects, CancellationCheck cancelled) {
        if (action.isDocumentHandler()) {
            return applyDocument(action, bound, context);
        }
        return applyRows(action, bound, context, rejects, cancelled);
    }

    /**
     * Applies every row as one business fact, in one transaction.
     *
     * <p>The stream handed to the handler is lazy over the artifact, so a document of a hundred thousand rows
     * costs one row of heap - but it does hold one transaction for the whole traversal, which is why a large
     * document belongs on a {@code DEFERRED} action. That is stated on {@link
     * ru.ludwigandreas.fileaction.api.DocumentHandler} as well, where the author will read it.
     */
    private <R> ApplyOutcome applyDocument(ResolvedAction<R> action, Path bound,
                                           FileActionContext context) {
        return transactions.execute(status -> {
            try (Stream<BoundRow<R>> rows = boundRows.stream(bound, action.binding().rowType())) {
                // The handler sees the author's own row type, not the envelope: a handler's job is the domain,
                // and the address is only there so that a reject can name a row.
                RowOutcome outcome = action.asDocumentHandler()
                        .apply(rows.map(BoundRow::payload), context);
                if (outcome == null || !outcome.isApplied()) {
                    status.setRollbackOnly();
                    return ApplyOutcome.documentRejected(outcome);
                }
                return ApplyOutcome.documentApplied();
            } catch (IOException failed) {
                throw new UncheckedIOException("the bound rows could not be read back", failed);
            }
        });
    }

    private <R> ApplyOutcome applyRows(ResolvedAction<R> action, Path bound, FileActionContext context,
                                       RejectCollector rejects, CancellationCheck cancelled) {
        long applied = 0;
        boolean stopped = false;
        Integer failedBatch = null;
        int batchNumber = 0;

        try (Stream<BoundRow<R>> rows = boundRows.stream(bound, action.binding().rowType())) {
            Iterator<BoundRow<R>> iterator = rows.iterator();
            if (action.commitPolicy() == CommitPolicy.ALL_OR_NOTHING) {
                return applyAllOrNothing(action, context, rejects, iterator);
            }
            while (iterator.hasNext()) {
                if (cancelled.isCancelled()) {
                    stopped = true;
                    break;
                }
                List<BoundRow<R>> batch = nextBatch(iterator, action.batchSize());
                batchNumber++;
                BatchResult result = applyBatch(action, context, rejects, batch, batchNumber);
                applied += result.applied();
                if (result.failed()) {
                    failedBatch = batchNumber;
                    break;
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("the bound rows could not be read back", failed);
        }
        return new ApplyOutcome(applied, stopped, failedBatch, null);
    }

    /**
     * Applies everything inside a single transaction, rolling it all back if any row is refused.
     *
     * <p>The read of the artifact happens inside that transaction, so a database connection is held for the
     * whole traversal. There is no arrangement that avoids it: the promise of this policy is that nothing is
     * applied unless everything can be, and that is a property of one transaction. An action whose files are
     * large enough for this to matter should be {@code DEFERRED}, where the connection is a worker's rather
     * than a request's.
     */
    private <R> ApplyOutcome applyAllOrNothing(ResolvedAction<R> action, FileActionContext context,
                                               RejectCollector rejects, Iterator<BoundRow<R>> rows) {
        return transactions.execute(status -> {
            long applied = 0;
            while (rows.hasNext()) {
                BoundRow<R> row = rows.next();
                RowOutcome outcome = require(action, action.asRowHandler().apply(row.payload(), context));
                if (outcome.isApplied()) {
                    applied++;
                    continue;
                }
                if (outcome.countsAsReject()) {
                    rejects.rejectRow(List.of(RowProblem.of(row.address(), outcome.code(),
                            outcome.args().toArray(new String[0]))));
                    status.setRollbackOnly();
                    // Returned rather than thrown: setRollbackOnly has already decided the data's fate, and
                    // an exception here would be a failure the caller has to interpret rather than the
                    // documented outcome of this policy.
                    return ApplyOutcome.allOrNothingRejected();
                }
                rejects.skipRow();
            }
            return new ApplyOutcome(applied, false, null, null);
        });
    }

    /**
     * Rejects a handler that said nothing.
     *
     * <p>One message for both transaction boundaries, so that whichever path a deployment is on, the defect
     * reads the same and a test asserting on it is not asserting on which policy was configured.
     */
    private static RowOutcome require(ResolvedAction<?> action, RowOutcome outcome) {
        if (outcome == null) {
            throw new IllegalStateException("action '" + action.name() + "' returned no RowOutcome; a handler"
                    + " must say what it did with the row");
        }
        return outcome;
    }

    private <R> BatchResult applyBatch(ResolvedAction<R> action, FileActionContext context,
                                       RejectCollector rejects, List<BoundRow<R>> batch,
                                       int batchNumber) {
        return transactions.execute(status -> {
            long applied = 0;
            for (BoundRow<R> row : batch) {
                RowOutcome outcome = require(action, action.asRowHandler().apply(row.payload(), context));
                if (outcome.isApplied()) {
                    applied++;
                    continue;
                }
                if (!outcome.countsAsReject()) {
                    rejects.skipRow();
                    continue;
                }
                // Recorded with the row's address, whatever the policy. A reject the user cannot locate is
                // the report they complain about, and the address is the only reason BoundRow exists.
                rejects.rejectRow(List.of(RowProblem.of(row.address(), outcome.code(),
                        outcome.args().toArray(new String[0]))));
                if (action.commitPolicy() == CommitPolicy.PER_BATCH) {
                    status.setRollbackOnly();
                    return new BatchResult(0, true);
                }
            }
            return new BatchResult(applied, false);
        });
    }

    private static <R> List<BoundRow<R>> nextBatch(Iterator<BoundRow<R>> rows, int size) {
        List<BoundRow<R>> batch = new ArrayList<>(size);
        while (rows.hasNext() && batch.size() < size) {
            batch.add(rows.next());
        }
        return batch;
    }

    /** One batch's result. */
    private record BatchResult(long applied, boolean failed) {
    }
}
