package ru.ludwigandreas.webcore.operation;

/**
 * Asked periodically by a running operation: should I stop?
 *
 * <h2>Cooperative, because nothing else is safe</h2>
 *
 * <p>Lifted from the export engine's {@code RunCancellation}, whose reasoning generalizes to every
 * long-running operation on this platform. Interrupting the worker thread would land somewhere
 * arbitrary - mid-page in a JDBC driver, mid-flush in a writer - and leave the engine to work out
 * which of its resources had been half-released. A flag checked at a boundary the operation chooses
 * means cancellation always takes effect at a point where the stream can be closed, the writer
 * closed and the temp file deleted, in that order, with nothing in between.
 *
 * <p>The cost is stated rather than hidden: an operation stops at its <em>next</em> check, not at
 * the moment somebody asks. That is precisely why a cancellation request is answered with
 * {@code 202} and not {@code 204} - see {@link OperationResponses#cancellationRequested}.
 *
 * <h2>Where the check goes</h2>
 *
 * <p>At whatever boundary the operation is already granular in - a window, a batch, a file - and not
 * per record. A check per record is millions of volatile reads for a latency improvement measured in
 * milliseconds, and the honest statement to make in a module's README is the bound: "stops within
 * one window", with the window named.
 */
@FunctionalInterface
public interface Cancellation {

    /** An operation nothing can cancel, for a synchronous execution the caller is waiting on. */
    Cancellation NEVER = () -> false;

    /** Whether the operation should stop at its next check. */
    boolean isCancelled();
}
