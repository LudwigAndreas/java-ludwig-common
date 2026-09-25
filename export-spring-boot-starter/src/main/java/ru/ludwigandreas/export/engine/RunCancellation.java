package ru.ludwigandreas.export.engine;

/**
 * Asked once per window: should this run stop?
 *
 * <p>Polled rather than interrupt-driven, and polled at window boundaries rather than per row. Both
 * are deliberate.
 *
 * <p>Interrupting the thread would land somewhere arbitrary - mid-page in the JDBC driver, mid-flush
 * in the writer - and leave the engine to work out which of its resources had been half-released. A
 * flag checked at a boundary means cancellation always takes effect at a point where the stream can
 * be closed, the writer closed and the temp file deleted, in that order, with nothing in between.
 *
 * <p>Per window rather than per row because the window is what the whole engine is granular in, and
 * because a check per row at the design point is a million volatile reads for a latency improvement
 * of milliseconds. The cost is stated rather than hidden: a cancelled run stops within one window,
 * which is bounded by {@code ludwig.export.window-size} and the slowest partner call in it.
 */
@FunctionalInterface
public interface RunCancellation {

    /** A run nothing can cancel, for a synchronous execution the caller is waiting on. */
    RunCancellation NEVER = () -> false;

    /** Whether the run should stop at the next window boundary. */
    boolean isCancelled();
}
